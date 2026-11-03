import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the Aura Points mod in a mods folder up to date. Prism runs it before every launch:
 *   "$INST_JAVA" -jar "$INST_DIR/aura-updater.jar" "$INST_MC_DIR/mods"
 * A server's start script runs it the same way, with --server.
 *
 * It works out the mod loader first (NeoForge or Fabric: from Prism's mmc-pack.json, a server's own files,
 * or the jars already in the folder; --loader neoforge|fabric overrides it) and reads that loader's manifest
 * from the GitHub repo (latest-neoforge.json or latest-fabric.json: {"version", "file", "url",
 * "sha256", "requires"}). If the mods folder doesn't hold exactly that jar, it downloads it, checks the SHA-256,
 * puts it in, and only then removes every other copy of the mod (found by its mod id, whatever it is called).
 *
 * Usage: java -jar aura-updater.jar <mods folder> [manifest folder url] [--loader neoforge|fabric] [--server]
 * (latest.json in the repo is only a copy of latest-neoforge.json for updaters older than this one.)
 *
 * It never stops the game from starting: every problem (offline, GitHub down, a locked file) is only
 * printed, the exit code is always 0, and a watchdog ends it after WATCHDOG_SECONDS no matter what.
 */
public final class AuraUpdater {

	static final String MANIFESTS = "https://raw.githubusercontent.com/ToastbutBuried/aura-points/main/";
	static final String MOD_ID = "aurapoints";
	static final int WATCHDOG_SECONDS = 45;
	static final String TMP = ".aura-points-download.tmp"; // not a .jar, so the game never loads it half-written

	static volatile Path mods;
	/** "neoforge" or "fabric" */
	static String loader;
	/** a dedicated server: client-only mods (Player Animator) are left out */
	static boolean server;

	public static void main(String[] args) {
		// whatever happens below, the launch goes on within WATCHDOG_SECONDS
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(WATCHDOG_SECONDS * 1000L);
			} catch (InterruptedException e) {
				return;
			}
			log("took too long - skipped, starting with the jar you have");
			cleanTmp();
			Runtime.getRuntime().halt(0);
		});
		watchdog.setDaemon(true);
		watchdog.start();
		try {
			List<String> pos = new ArrayList<>();
			String forced = null;
			for (int i = 0; i < args.length; i++) {
				if (args[i].equals("--server")) server = true;
				else if (args[i].equals("--loader") && i + 1 < args.length) forced = args[++i].toLowerCase(java.util.Locale.ROOT);
				else pos.add(args[i]);
			}
			if (pos.isEmpty()) {
				log("usage: java -jar aura-updater.jar <mods folder> [manifest folder url] [--loader neoforge|fabric] [--server]");
			} else {
				mods = Path.of(pos.get(0)).toAbsolutePath().normalize();
				if (!server) server = looksLikeServer();
				loader = forced != null ? forced : detectLoader();
				if (!loader.equals("neoforge") && !loader.equals("fabric")) throw new IllegalStateException("unknown loader " + loader);
				String base = pos.size() > 1 ? pos.get(1) : MANIFESTS;
				String manifest = (base.endsWith("/") ? base : base + "/") + "latest-" + loader + ".json";
				log((loader.equals("fabric") ? "Fabric" : "NeoForge") + (server ? " server" : "") + " instance");
				run(manifest);
			}
		} catch (Throwable t) {
			log("skipped (" + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage())
					+ ") - starting with the jar you have");
			cleanTmp();
		}
		System.exit(0);
	}

	static void run(String manifestUrl) throws Exception {
		if (!Files.isDirectory(mods)) throw new IllegalStateException("no mods folder at " + mods);
		cleanTmp(); // a leftover from a run that was cut off
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NORMAL).build();

		// a query string so GitHub's raw cache never hands out an old manifest
		HttpResponse<String> manifest = http.send(HttpRequest.newBuilder(URI.create(manifestUrl + "?t=" + System.currentTimeMillis()))
				.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
		if (manifest.statusCode() != 200) throw new IllegalStateException(manifest.uri().getPath() + " returned HTTP " + manifest.statusCode());
		String json = manifest.body();
		String version = field(json, "version"), file = field(json, "file"), url = field(json, "url"), sha = field(json, "sha256");
		String forLoader = optField(json, "loader");
		if (forLoader != null && !forLoader.equals(loader)) throw new IllegalStateException("that manifest is for " + forLoader + ", this instance is " + loader);
		if (!file.matches("aura-points-[A-Za-z0-9.+_-]+\\.jar")) throw new IllegalStateException("odd file name in the manifest: " + file);
		if (!sha.matches("[0-9a-fA-F]{64}")) throw new IllegalStateException("odd checksum in the manifest");
		if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1")) throw new IllegalStateException("download url must be https");

		// mods Aura Points needs (e.g. GeckoLib): installed if missing, never removed or swapped
		ensureRequired(http, json);

		Path target = mods.resolve(file);
		if (Files.isRegularFile(target) && sha256(target).equalsIgnoreCase(sha)) {
			removeOthers(target);
			log("Aura Points " + version + " is up to date");
			return;
		}

		log("downloading Aura Points " + version + " ...");
		Path tmp = mods.resolve(TMP);
		HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() != 200) throw new IllegalStateException("download returned HTTP " + res.statusCode());
		try (InputStream in = res.body()) {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		if (!sha256(tmp).equalsIgnoreCase(sha)) throw new IllegalStateException("checksum mismatch, download thrown away");

		// the new jar goes in first; only once it is there do the old ones go - and if that fails,
		// the new one is taken back out, so there are never two copies of the mod (that would crash)
		boolean hadTarget = Files.exists(target);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		try {
			removeOthers(target);
		} catch (Exception e) {
			if (!hadTarget) Files.deleteIfExists(target);
			throw e;
		}
		log("installed Aura Points " + version);
	}

	/**
	 * The manifest may end with "requires": [{"modid", "name", "file", "url", "sha256"}, ...] - mods the new Aura Points
	 * needs. Each one whose mod id no jar in the mods folder has is downloaded (checksum checked) and put in. A mod
	 * that is already there in any version is left alone. One failing doesn't stop the others or Aura Points.
	 */
	static void ensureRequired(HttpClient http, String json) {
		int at = json.indexOf("\"requires\"");
		if (at < 0) return;
		Matcher obj = Pattern.compile("\\{[^{}]*\\}").matcher(json.substring(at));
		while (obj.find()) {
			String o = obj.group();
			try {
				String modid = field(o, "modid"), name = field(o, "name"), file = field(o, "file"), url = field(o, "url"), sha = field(o, "sha256");
				if ("client".equals(optField(o, "side")) && server) continue;
				if (!modid.matches("[a-z0-9_-]+") || !file.matches("[A-Za-z0-9.+_-]+\\.jar") || !sha.matches("[0-9a-fA-F]{64}")
						|| !url.startsWith("https://")) throw new IllegalStateException("odd entry");
				if (hasMod(modid)) continue;
				log("downloading " + name + " (needed by Aura Points) ...");
				Path tmp = mods.resolve(TMP);
				HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
						HttpResponse.BodyHandlers.ofInputStream());
				if (res.statusCode() != 200) throw new IllegalStateException("download returned HTTP " + res.statusCode());
				try (InputStream in = res.body()) {
					Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				}
				if (!sha256(tmp).equalsIgnoreCase(sha)) throw new IllegalStateException("checksum mismatch, download thrown away");
				Files.move(tmp, mods.resolve(file), StandardCopyOption.REPLACE_EXISTING);
				log("installed " + name);
			} catch (Exception e) {
				cleanTmp();
				log("could not install a needed mod (" + e.getMessage() + ") - install it by hand if the game says it's missing");
			}
		}
	}

	/** Some jar in the mods folder declares this mod id. */
	static boolean hasMod(String modid) throws Exception {
		try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
			for (Path p : jars) if (declares(p, modid)) return true;
		}
		return false;
	}

	/** The jar declares this mod id: neoforge.mods.toml modId, or fabric.mod.json id / provides. */
	static boolean declares(Path jar, String modid) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			String q = Pattern.quote(modid);
			String toml = entry(zip, "META-INF/neoforge.mods.toml");
			if (toml != null && tomlDeclares(toml, modid)) return true;
			String fmj = entry(zip, "fabric.mod.json");
			return fmj != null && (Pattern.compile("\"id\"\\s*:\\s*\"" + q + "\"").matcher(fmj).find()
					|| Pattern.compile("\"provides\"\\s*:\\s*\\[[^\\]]*\"" + q + "\"").matcher(fmj).find());
		} catch (Exception e) {
			return false; // not a readable jar - not ours to touch
		}
	}

	/** modId = "x" inside a [[mods]] table - not the [[dependencies.*]] ones, which name other mods. */
	static boolean tomlDeclares(String toml, String modid) {
		boolean inMods = false;
		for (String line : toml.split("\\R")) {
			String t = line.trim();
			if (t.startsWith("[")) inMods = t.replace(" ", "").equals("[[mods]]");
			else if (inMods && Pattern.compile("^modId\\s*=\\s*[\"']" + Pattern.quote(modid) + "[\"']").matcher(t).find()) return true;
		}
		return false;
	}

	static String entry(ZipFile zip, String name) throws Exception {
		ZipEntry e = zip.getEntry(name);
		if (e == null) return null;
		try (InputStream in = zip.getInputStream(e)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// ---- which loader, which side ----

	/**
	 * Prism/MultiMC: mmc-pack.json in the instance folder (two up from mods). A server: its launcher files next to
	 * mods. Anything else: whichever loader most jars in the folder are made for. NeoForge if nothing says.
	 */
	static String detectLoader() {
		Path mc = mods.getParent(), inst = mc == null ? null : mc.getParent();
		for (Path dir : new Path[]{inst, mc}) {
			if (dir == null) continue;
			try {
				Path pack = dir.resolve("mmc-pack.json");
				if (Files.isRegularFile(pack)) {
					String s = Files.readString(pack);
					if (s.contains("\"net.neoforged\"")) return "neoforge";
					if (s.contains("\"net.fabricmc.fabric-loader\"") || s.contains("\"org.quiltmc.quilt-loader\"")) return "fabric";
				}
			} catch (Exception ignored) {
				// fall through to the other checks
			}
		}
		if (mc != null) {
			if (Files.exists(mc.resolve("fabric-server-launch.jar")) || Files.isDirectory(mc.resolve(".fabric"))) return "fabric";
			if (Files.isDirectory(mc.resolve("libraries/net/neoforged"))) return "neoforge";
		}
		int neo = 0, fabric = 0;
		try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
			for (Path p : jars) {
				try (ZipFile zip = new ZipFile(p.toFile())) {
					// NeoForge first: Forgified Fabric API style jars carry both files
					if (zip.getEntry("META-INF/neoforge.mods.toml") != null) neo++;
					else if (zip.getEntry("fabric.mod.json") != null) fabric++;
				} catch (Exception ignored) {
					// not a jar
				}
			}
		} catch (Exception ignored) {
			// no answer from the jars
		}
		return fabric > neo ? "fabric" : "neoforge";
	}

	/** A dedicated server folder: server.properties / eula.txt / a server launcher next to mods. */
	static boolean looksLikeServer() {
		Path dir = mods.getParent();
		if (dir == null || Files.isRegularFile(dir.resolve("options.txt"))) return false;
		return Files.isRegularFile(dir.resolve("server.properties")) || Files.isRegularFile(dir.resolve("eula.txt"))
				|| Files.exists(dir.resolve("fabric-server-launch.jar"));
	}

	/** Every other copy of the mod goes: any .jar whose mod id is aurapoints, whatever it is named. */
	static void removeOthers(Path keep) throws Exception {
		List<Path> old = new ArrayList<>();
		try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
			for (Path p : jars) {
				if (!p.getFileName().equals(keep.getFileName()) && isAuraPoints(p)) old.add(p);
			}
		}
		for (Path p : old) {
			Files.delete(p);
			log("removed old " + p.getFileName());
		}
	}

	/** Any copy of the mod, for either loader (a NeoForge jar in a Fabric folder goes too). */
	static boolean isAuraPoints(Path jar) {
		String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		return name.startsWith("aura-points-") || declares(jar, MOD_ID);
	}

	static void cleanTmp() {
		try {
			if (mods != null) Files.deleteIfExists(mods.resolve(TMP));
		} catch (Exception ignored) {
			// nothing to do
		}
	}

	static String field(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		if (!m.find()) throw new IllegalStateException("the manifest has no " + key);
		return m.group(1);
	}

	static String optField(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		return m.find() ? m.group(1) : null;
	}

	static String sha256(Path p) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(Files.readAllBytes(p));
		return HexFormat.of().formatHex(md.digest());
	}

	static void log(String s) {
		System.out.println("[aura-updater] " + s);
		System.out.flush();
	}
}
