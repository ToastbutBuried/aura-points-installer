import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Aura Points server installer. Asks for an instance name and a mod set ("needed" = just what you
 * need to join, "server" = everything the server runs that also works on your side), finds the
 * launchers on this computer (Prism, MultiMC, Modrinth App, ATLauncher, the official launcher),
 * downloads every mod from its official source (links and checksums in modpack.json - no mod jars
 * live in the repo) and creates a new instance with them.
 *
 * Usage: java -jar aura-installer.jar [--name NAME] [--set needed|server] [--launcher ID] [--yes]
 *        [--manifest URL|FILE] [--dest DIR]   (flags are for testing; without them it asks)
 * Java 17+, no dependencies.
 */
public final class AuraInstaller {

	static final String REPO_RAW = "https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main/";
	/** The update manifest lives next to the mod's source (ToastbutBuried/aura-points), written by every release. */
	static final String LATEST = "https://raw.githubusercontent.com/ToastbutBuried/aura-points/main/latest.json";
	static final String UA = "ToastbutBuried/aura-points-installer";
	static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(20)).build();
	static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
	static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	static final boolean WIN = OS.contains("win"), MAC = OS.contains("mac");
	static final Path HOME = Path.of(System.getProperty("user.home"));

	static final Map<String, String> ARGS = new LinkedHashMap<>();

	public static void main(String[] args) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("--")) {
				String k = args[i].substring(2);
				String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
				ARGS.put(k, v);
			}
		}
		try {
			run();
		} catch (Exception e) {
			System.out.println();
			System.out.println("  Something went wrong: " + e.getMessage());
			System.out.println("  Nothing half-made was left in your launcher. Send this to Toast if it keeps happening.");
			if (ARGS.containsKey("debug")) e.printStackTrace();
			pause();
			System.exit(1);
		}
		pause();
	}

	// ---------------------------------------------------------------
	// The flow
	// ---------------------------------------------------------------

	static void run() throws Exception {
		System.out.println();
		System.out.println("  == Aura Points server installer ==");
		System.out.println("  " + (WIN ? "Windows" : MAC ? "macOS" : "Linux") + " detected.");
		System.out.println();

		// the mod list and the newest Aura Points
		Map<String, Object> pack = obj(Json.parse(text(ARGS.getOrDefault("manifest", REPO_RAW + "modpack.json"))));
		Map<String, Object> latest = obj(Json.parse(text(LATEST)));
		String mc = str(pack.get("minecraft")), neo = str(pack.get("neoforge"));
		List<Map<String, Object>> all = new ArrayList<>();
		for (Object o : list(pack.get("mods"))) all.add(obj(o));
		long neededCount = all.stream().filter(m -> "needed".equals(m.get("set"))).count() + 1;

		// 1. name, 2. set
		String name = ARGS.containsKey("name") ? ARGS.get("name") : ask("Name for the new instance", "Aura Server");
		name = cleanName(name);
		String set = ARGS.get("set");
		if (set == null) {
			System.out.println();
			System.out.println("  Which mods?");
			System.out.println("   1) Needed       - just what you need to join (" + neededCount + " mods)");
			System.out.println("   2) Server mods  - everything the server runs that works on your side (" + (all.size() + 1)
					+ " mods: adds maps, JEI, Distant Horizons, Do a Barrel Roll, Terralith and performance mods)");
			set = choose("Pick 1 or 2", 2, 1) == 1 ? "needed" : "server";
		}
		final String chosen = set;
		List<Map<String, Object>> mods = new ArrayList<>();
		for (Map<String, Object> m : all) if ("needed".equals(m.get("set")) || "server".equals(chosen)) mods.add(m);

		// 3. launcher
		List<Launcher> found = Launcher.detect();
		Launcher target = pickLauncher(found);

		// 4. download everything into a staging folder first, so a failed download leaves nothing behind
		Path stage = Files.createTempDirectory("aura-install");
		System.out.println();
		System.out.println("  Downloading " + (mods.size() + 1) + " mods ...");
		List<Mod> files = download(mods, latest, stage.resolve("mods"));

		// 5. build the instance
		System.out.println();
		target.install(name, mc, neo, files, stage);
		deleteTree(stage);
	}

	static Launcher pickLauncher(List<Launcher> found) throws IOException {
		String want = ARGS.get("launcher");
		if (want != null) {
			for (Launcher l : found) if (l.id.equalsIgnoreCase(want)) return l;
			if (want.equals("folder")) return Launcher.folder();
			throw new IllegalStateException("launcher '" + want + "' not found here");
		}
		System.out.println();
		List<Launcher> options = new ArrayList<>(found);
		options.add(Launcher.folder());
		if (found.isEmpty()) {
			System.out.println("  I couldn't find Prism Launcher, MultiMC, the Modrinth App, ATLauncher or the Minecraft Launcher.");
			System.out.println("  (Prism Launcher is the easiest: https://prismlauncher.org)");
		} else {
			System.out.println("  Found on this computer:");
		}
		for (int i = 0; i < options.size(); i++) System.out.println("   " + (i + 1) + ") " + options.get(i).describe());
		return options.get(choose("Install into which one?", options.size(), 1) - 1);
	}

	// ---------------------------------------------------------------
	// Downloads
	// ---------------------------------------------------------------

	/** A downloaded mod: where it is now, where it came from, its hashes. */
	record Mod(String name, String file, String url, String sha1, String sha512, long size, Path path, boolean modrinthHost) {
	}

	static List<Mod> download(List<Map<String, Object>> mods, Map<String, Object> latest, Path dir) throws Exception {
		Files.createDirectories(dir);
		ExecutorService pool = Executors.newFixedThreadPool(6);
		List<Future<Mod>> jobs = new ArrayList<>();
		int total = mods.size() + 1;
		int[] done = {0};
		for (Map<String, Object> m : mods) {
			jobs.add(pool.submit(() -> {
				String file = str(m.get("file")), url = str(m.get("url")), sha1 = str(m.get("sha1"));
				Path p = dir.resolve(file);
				byte[] data = fetchVerified(url, "SHA-1", sha1, file);
				Files.write(p, data);
				progress(done, total, str(m.get("name")));
				return new Mod(str(m.get("name")), file, url, sha1, str(m.get("sha512")), ((Number) m.get("size")).longValue(), p,
						allowedInMrpack(url));
			}));
		}
		jobs.add(pool.submit(() -> {
			String file = str(latest.get("file")), url = str(latest.get("url"));
			byte[] data = fetchVerified(url, "SHA-256", str(latest.get("sha256")), file);
			Path p = dir.resolve(file);
			Files.write(p, data);
			progress(done, total, "Aura Points " + latest.get("version"));
			return new Mod("Aura Points", file, url, hex("SHA-1", data), hex("SHA-512", data), data.length, p, true);
		}));
		pool.shutdown();
		List<Mod> out = new ArrayList<>();
		try {
			for (Future<Mod> f : jobs) out.add(f.get());
		} catch (java.util.concurrent.ExecutionException e) {
			pool.shutdownNow();
			throw e.getCause() instanceof Exception ex ? ex : e;
		}
		return out;
	}

	static synchronized void progress(int[] done, int total, String what) {
		done[0]++;
		System.out.printf("   [%2d/%d] %s%n", done[0], total, what);
	}

	/** Downloads, checks the hash, tries twice more on failure. */
	static byte[] fetchVerified(String url, String algo, String expect, String label) throws Exception {
		Exception last = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				byte[] data = bytes(url);
				String got = hex(algo, data);
				if (got.equalsIgnoreCase(expect)) return data;
				last = new IOException(label + " did not match its checksum");
			} catch (Exception e) {
				last = e;
			}
		}
		throw new IOException("could not download " + label + " (" + last.getMessage() + ")");
	}

	/** Modrinth packs may only point at these hosts; anything else is packed into the file itself. */
	static boolean allowedInMrpack(String url) {
		String host = URI.create(url).getHost();
		return host != null && (host.equals("cdn.modrinth.com") || host.equals("github.com")
				|| host.equals("raw.githubusercontent.com") || host.equals("gitlab.com"));
	}

	static byte[] bytes(String url) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).timeout(Duration.ofMinutes(3)).GET().build();
		HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
		return r.body();
	}

	static String text(String urlOrFile) throws IOException, InterruptedException {
		if (!urlOrFile.startsWith("http")) return Files.readString(Path.of(urlOrFile));
		return new String(bytes(urlOrFile), StandardCharsets.UTF_8);
	}

	static String hex(String algo, byte[] data) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (byte b : MessageDigest.getInstance(algo).digest(data)) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	// ---------------------------------------------------------------
	// Launchers
	// ---------------------------------------------------------------

	abstract static class Launcher {
		final String id, label;
		final Path dir;

		Launcher(String id, String label, Path dir) {
			this.id = id;
			this.label = label;
			this.dir = dir;
		}

		String describe() {
			return label + (dir != null ? "  (" + dir + ")" : "");
		}

		abstract void install(String name, String mc, String neo, List<Mod> mods, Path stage) throws Exception;

		static List<Launcher> detect() {
			List<Launcher> out = new ArrayList<>();
			Path appData = WIN ? Path.of(env("APPDATA", HOME.resolve("AppData/Roaming").toString())) : null;
			Path support = MAC ? HOME.resolve("Library/Application Support") : null;
			Path share = !WIN && !MAC ? Path.of(env("XDG_DATA_HOME", HOME.resolve(".local/share").toString())) : null;

			// Prism (normal, Flatpak, Scoop) and MultiMC: same instance format
			List<Path> prism = new ArrayList<>();
			if (WIN) {
				prism.add(appData.resolve("PrismLauncher"));
				prism.add(HOME.resolve("scoop/persist/prismlauncher"));
			} else if (MAC) {
				prism.add(support.resolve("PrismLauncher"));
			} else {
				prism.add(share.resolve("PrismLauncher"));
				prism.add(HOME.resolve(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"));
			}
			for (Path p : prism) if (Files.isRegularFile(p.resolve("prismlauncher.cfg"))) out.add(new MMC("prism", "Prism Launcher", p, "prismlauncher.cfg"));
			List<Path> multimc = new ArrayList<>(List.of(HOME.resolve("MultiMC"), HOME.resolve("Desktop/MultiMC"), HOME.resolve("Downloads/MultiMC")));
			if (WIN) multimc.add(Path.of("C:/MultiMC"));
			if (MAC) multimc.add(Path.of("/Applications/MultiMC.app/Data"));
			if (share != null) multimc.add(share.resolve("multimc"));
			for (Path p : multimc) if (Files.isRegularFile(p.resolve("multimc.cfg"))) out.add(new MMC("multimc", "MultiMC", p, "multimc.cfg"));

			// Modrinth App and ATLauncher import .mrpack files
			Path base = WIN ? appData : MAC ? support : share;
			for (String n : List.of("ModrinthApp", "com.modrinth.theseus")) {
				if (Files.isDirectory(base.resolve(n))) {
					out.add(new Mrpack("modrinth", "Modrinth App", base.resolve(n)));
					break;
				}
			}
			for (Path p : List.of(base.resolve("ATLauncher"), base.resolve("atlauncher"), HOME.resolve(".atlauncher"))) {
				if (Files.isDirectory(p)) {
					out.add(new Mrpack("atlauncher", "ATLauncher", p));
					break;
				}
			}

			// the official launcher
			Path dotmc = WIN ? appData.resolve(".minecraft") : MAC ? support.resolve("minecraft") : HOME.resolve(".minecraft");
			if (Files.isRegularFile(dotmc.resolve("launcher_profiles.json"))) out.add(new Official(dotmc));
			return out;
		}

		static Launcher folder() {
			return new Folder();
		}
	}

	/** The auto-updater jar (checked to be a real jar), or null after saying why it couldn't be fetched. */
	static byte[] updaterJar() {
		Exception last = null;
		for (String url : List.of(REPO_RAW + "aura-updater.jar", "https://github.com/ToastbutBuried/aura-points-installer/raw/main/aura-updater.jar")) {
			for (int attempt = 0; attempt < 2; attempt++) {
				try {
					byte[] jar = bytes(url);
					if (jar.length > 4 && jar[0] == 'P' && jar[1] == 'K') return jar; // a zip, not an error page
					last = new IOException("got something that isn't a jar");
				} catch (Exception e) {
					last = e;
				}
			}
		}
		System.out.println("  WARNING: couldn't download the auto-updater (" + (last == null ? "?" : last.getMessage()) + ").");
		System.out.println("  The instance works, but Aura Points won't update by itself - see the README to add it by hand.");
		return null;
	}

	/** Prism Launcher / MultiMC: a folder with instance.cfg, mmc-pack.json and minecraft/mods. */
	static final class MMC extends Launcher {
		final String cfg;

		MMC(String id, String label, Path dir, String cfg) {
			super(id, label, dir);
			this.cfg = cfg;
		}

		Path instances() throws IOException {
			String instDir = "instances";
			for (String line : Files.readAllLines(dir.resolve(cfg))) {
				if (line.startsWith("InstanceDir=")) instDir = unquote(line.substring("InstanceDir=".length()).trim());
			}
			Path p = Path.of(instDir);
			return p.isAbsolute() ? p : dir.resolve(instDir);
		}

		@Override
		void install(String name, String mc, String neo, List<Mod> mods, Path stage) throws Exception {
			// The launcher watches its instances folder: an instance that shows up half-built (no instance.cfg yet)
			// is taken for broken and never looked at again. So, like Prism itself, build it in instances/.tmp
			// and move it in at the end in one step.
			Path instances = instances();
			Path inst = unique(instances, folderName(name));
			Path build = instances.resolve(".tmp").resolve("aura-" + Long.toHexString(System.nanoTime()));
			Path modsDir = build.resolve("minecraft/mods");
			Files.createDirectories(modsDir);
			for (Mod m : mods) Files.copy(m.path(), modsDir.resolve(m.file()), StandardCopyOption.REPLACE_EXISTING);

			StringBuilder c = new StringBuilder("[General]\n");
			c.append("ConfigVersion=1.2\n");
			c.append("InstanceType=OneSix\n");
			c.append("iconKey=default\n");
			c.append("name=").append(name).append('\n');
			c.append("notes=Made by the Aura Points installer. Aura Points updates itself every time you start the game.\n");
			// Aura Points keeps itself current: the updater runs before every launch (Qt's own escaping of the quotes)
			byte[] jar = updaterJar();
			if (jar != null) {
				Files.write(build.resolve("aura-updater.jar"), jar);
				c.append("OverrideCommands=true\n");
				c.append("PreLaunchCommand=\\\"$INST_JAVA\\\" -jar \\\"$INST_DIR/aura-updater.jar\\\" \\\"$INST_MC_DIR/mods\\\"\n");
				c.append("PostExitCommand=\nWrapperCommand=\n");
			}
			Files.writeString(build.resolve("instance.cfg"), c.toString());
			Files.writeString(build.resolve("mmc-pack.json"), """
					{
					    "components": [
					        {
					            "uid": "net.minecraft",
					            "version": "%s",
					            "important": true
					        },
					        {
					            "uid": "net.neoforged",
					            "version": "%s"
					        }
					    ],
					    "formatVersion": 1
					}
					""".formatted(mc, neo));
			try {
				Files.move(build, inst, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(build, inst);
			}
			System.out.println("  OK - Created the instance \"" + name + "\" in " + label + ":");
			System.out.println("    " + inst);
			System.out.println();
			System.out.println("  It shows up in " + label + " right away (if it doesn't, restart " + label + ").");
			System.out.println("  On the first start it downloads Minecraft " + mc + " and NeoForge " + neo + " by itself.");
		}
	}

	/** Modrinth App / ATLauncher: a .mrpack file they import. */
	static final class Mrpack extends Launcher {
		Mrpack(String id, String label, Path dir) {
			super(id, label, dir);
		}

		@Override
		String describe() {
			return label + "  (I make a modpack file and open it - " + label + " imports it)";
		}

		@Override
		void install(String name, String mc, String neo, List<Mod> mods, Path stage) throws Exception {
			Path out = unique(downloads(), folderName(name) + ".mrpack");
			writeMrpack(out, name, mc, neo, mods);
			System.out.println("  OK - Made the modpack file:");
			System.out.println("    " + out);
			System.out.println();
			if (id.equals("modrinth")) {
				System.out.println("  Modrinth App: it should open the file now. If not: + (Create) -> Import -> \"From file\" -> pick it.");
			} else {
				System.out.println("  ATLauncher: Instances -> Import -> pick that file.");
			}
			open(out);
		}
	}

	static void writeMrpack(Path out, String name, String mc, String neo, List<Mod> mods) throws Exception {
		StringBuilder files = new StringBuilder();
		List<Mod> bundled = new ArrayList<>();
		for (Mod m : mods) {
			if (!m.modrinthHost()) {
				bundled.add(m);
				continue;
			}
			if (files.length() > 0) files.append(",\n");
			files.append("    {\"path\": ").append(Json.quote("mods/" + m.file()))
					.append(", \"hashes\": {\"sha1\": ").append(Json.quote(m.sha1())).append(", \"sha512\": ").append(Json.quote(m.sha512()))
					.append("}, \"downloads\": [").append(Json.quote(m.url())).append("], \"fileSize\": ").append(m.size()).append('}');
		}
		String index = "{\n  \"formatVersion\": 1,\n  \"game\": \"minecraft\",\n  \"versionId\": \"aura-" + Instant.now().getEpochSecond() + "\",\n"
				+ "  \"name\": " + Json.quote(name) + ",\n  \"summary\": \"Aura Points server\",\n  \"files\": [\n" + files + "\n  ],\n"
				+ "  \"dependencies\": {\"minecraft\": " + Json.quote(mc) + ", \"neoforge\": " + Json.quote(neo) + "}\n}\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
			zip.putNextEntry(new ZipEntry("modrinth.index.json"));
			zip.write(index.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			// mods from other hosts (FTB's own download server) go inside the pack
			for (Mod m : bundled) {
				zip.putNextEntry(new ZipEntry("overrides/mods/" + m.file()));
				Files.copy(m.path(), zip);
				zip.closeEntry();
			}
		}
	}

	/** The official Minecraft Launcher: NeoForge installed into .minecraft, a profile with its own game folder. */
	static final class Official extends Launcher {
		Official(Path dotmc) {
			super("official", "Minecraft Launcher (the official one)", dotmc);
		}

		@Override
		void install(String name, String mc, String neo, List<Mod> mods, Path stage) throws Exception {
			String versionId = "neoforge-" + neo;
			if (!Files.isRegularFile(dir.resolve("versions/" + versionId + "/" + versionId + ".json"))) {
				System.out.println("  Installing NeoForge " + neo + " (takes a minute) ...");
				Path installer = stage.resolve("neoforge-installer.jar");
				Files.write(installer, bytes("https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neo + "/neoforge-" + neo + "-installer.jar"));
				String java = ProcessHandle.current().info().command().orElse("java");
				Process p = new ProcessBuilder(java, "-jar", installer.toString(), "--install-client", dir.toString())
						.directory(stage.toFile()).redirectErrorStream(true).start();
				drain(p.getInputStream());
				if (p.waitFor() != 0 || !Files.isRegularFile(dir.resolve("versions/" + versionId + "/" + versionId + ".json"))) {
					throw new IOException("the NeoForge installer failed (is the Minecraft Launcher open? close it and try again)");
				}
			}
			Path game = unique(dir.resolve("aura-instances"), folderName(name));
			Path modsDir = game.resolve("mods");
			Files.createDirectories(modsDir);
			for (Mod m : mods) Files.copy(m.path(), modsDir.resolve(m.file()), StandardCopyOption.REPLACE_EXISTING);

			// add the profile (a backup of the file first)
			Path profiles = dir.resolve("launcher_profiles.json");
			Files.copy(profiles, dir.resolve("launcher_profiles.json.before-aura"), StandardCopyOption.REPLACE_EXISTING);
			Map<String, Object> root = obj(Json.parse(Files.readString(profiles)));
			Map<String, Object> all = obj(root.computeIfAbsent("profiles", k -> new LinkedHashMap<String, Object>()));
			Map<String, Object> prof = new LinkedHashMap<>();
			String now = Instant.now().toString();
			prof.put("created", now);
			prof.put("gameDir", game.toString());
			prof.put("icon", "Lightning");
			prof.put("lastUsed", now);
			prof.put("lastVersionId", versionId);
			prof.put("name", name);
			prof.put("type", "custom");
			all.put("aura-" + Long.toHexString(System.currentTimeMillis()), prof);
			Files.writeString(profiles, Json.write(root, 0) + "\n");
			System.out.println("  OK - Added the installation \"" + name + "\" to the Minecraft Launcher.");
			System.out.println("    Its game folder (mods, worlds, settings): " + game);
			System.out.println();
			System.out.println("  Open the Minecraft Launcher -> Installations, or pick it next to the Play button.");
			System.out.println("  (Aura Points doesn't update itself here - run this installer again for a new version.)");
		}
	}

	/** No supported launcher: just a folder with the mods. */
	static final class Folder extends Launcher {
		Folder() {
			super("folder", "Just put the mods in a folder (set up the launcher yourself)", null);
		}

		@Override
		void install(String name, String mc, String neo, List<Mod> mods, Path stage) throws Exception {
			Path base = ARGS.containsKey("dest") ? Path.of(ARGS.get("dest")) : downloads();
			Path out = unique(base, folderName(name) + " mods");
			Files.createDirectories(out);
			for (Mod m : mods) Files.copy(m.path(), out.resolve(m.file()), StandardCopyOption.REPLACE_EXISTING);
			System.out.println("  OK - Put " + mods.size() + " mods in:");
			System.out.println("    " + out);
			System.out.println();
			System.out.println("  Make a Minecraft " + mc + " + NeoForge " + neo + " instance in your launcher and copy them into its mods folder.");
			open(out);
		}
	}

	// ---------------------------------------------------------------
	// Little helpers
	// ---------------------------------------------------------------

	static String ask(String q, String def) throws IOException {
		System.out.print("  " + q + (def != null ? " [" + def + "]" : "") + ": ");
		String s = IN.readLine();
		if (s == null) throw new IOException("no input (run it in a terminal)");
		s = s.trim();
		return s.isEmpty() && def != null ? def : s;
	}

	static int choose(String q, int max, int def) throws IOException {
		if (ARGS.containsKey("yes")) return def;
		while (true) {
			String s = ask(q, String.valueOf(def));
			try {
				int n = Integer.parseInt(s);
				if (n >= 1 && n <= max) return n;
			} catch (NumberFormatException ignored) {
			}
			System.out.println("  Type a number from 1 to " + max + ".");
		}
	}

	static void pause() {
		if (ARGS.containsKey("yes") || !WIN) return;
		System.out.print("\n  Press Enter to close.");
		try {
			IN.readLine();
		} catch (IOException ignored) {
		}
	}

	/** No characters launchers or file systems choke on. */
	static String cleanName(String s) {
		String c = s.replaceAll("[\\\\/:*?\"<>|;,=#\\[\\]\\p{Cntrl}]", "").trim();
		return c.isEmpty() ? "Aura Server" : c;
	}

	static String folderName(String name) {
		return name.replace(' ', '-');
	}

	/** {@code base/name}, or name-2, name-3 ... if that exists already. */
	static Path unique(Path base, String name) throws IOException {
		Files.createDirectories(base);
		String stem = name, ext = "";
		int dot = name.lastIndexOf('.');
		if (dot > 0 && name.endsWith(".mrpack")) {
			stem = name.substring(0, dot);
			ext = name.substring(dot);
		}
		Path p = base.resolve(name);
		for (int i = 2; Files.exists(p); i++) p = base.resolve(stem + "-" + i + ext);
		return p;
	}

	static Path downloads() {
		Path d = HOME.resolve("Downloads");
		return Files.isDirectory(d) ? d : HOME;
	}

	static void open(Path p) {
		if (ARGS.containsKey("yes")) return; // unattended / test runs
		try {
			String[] cmd = WIN ? new String[]{"explorer", p.toString()} : MAC ? new String[]{"open", p.toString()} : new String[]{"xdg-open", p.toString()};
			new ProcessBuilder(cmd).redirectErrorStream(true).start();
		} catch (Exception ignored) {
		}
	}

	static void drain(InputStream in) throws IOException {
		byte[] buf = new byte[8192];
		while (in.read(buf) >= 0) {
		}
	}

	static void deleteTree(Path p) {
		try (var s = Files.walk(p)) {
			s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
		} catch (IOException ignored) {
		}
	}

	static String env(String k, String def) {
		String v = System.getenv(k);
		return v == null || v.isBlank() ? def : v;
	}

	static String unquote(String s) {
		return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> obj(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object o) {
		return (List<Object>) o;
	}

	static String str(Object o) {
		return o == null ? null : o.toString();
	}

	// ---------------------------------------------------------------
	// Minimal JSON (keeps key order and numbers exactly as written, so launcher_profiles.json round-trips)
	// ---------------------------------------------------------------

	static final class Json {
		private final String s;
		private int i;

		private Json(String s) {
			this.s = s;
		}

		static Object parse(String s) {
			Json j = new Json(s);
			j.ws();
			Object v = j.value();
			j.ws();
			if (j.i != s.length()) throw new IllegalArgumentException("bad JSON at " + j.i);
			return v;
		}

		private void ws() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		}

		private Object value() {
			char c = s.charAt(i);
			if (c == '{') {
				i++;
				Map<String, Object> m = new LinkedHashMap<>();
				ws();
				if (s.charAt(i) == '}') {
					i++;
					return m;
				}
				while (true) {
					ws();
					String k = string();
					ws();
					expect(':');
					ws();
					m.put(k, value());
					ws();
					if (s.charAt(i) == ',') {
						i++;
						continue;
					}
					expect('}');
					return m;
				}
			}
			if (c == '[') {
				i++;
				List<Object> l = new ArrayList<>();
				ws();
				if (s.charAt(i) == ']') {
					i++;
					return l;
				}
				while (true) {
					ws();
					l.add(value());
					ws();
					if (s.charAt(i) == ',') {
						i++;
						continue;
					}
					expect(']');
					return l;
				}
			}
			if (c == '"') return string();
			if (s.startsWith("true", i)) {
				i += 4;
				return Boolean.TRUE;
			}
			if (s.startsWith("false", i)) {
				i += 5;
				return Boolean.FALSE;
			}
			if (s.startsWith("null", i)) {
				i += 4;
				return null;
			}
			int st = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
			if (st == i) throw new IllegalArgumentException("bad JSON at " + i);
			String t = s.substring(st, i);
			return t.matches("-?\\d{1,18}") ? (Number) Long.parseLong(t) : new NumWrap(t);
		}

		private void expect(char c) {
			if (s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
			i++;
		}

		private String string() {
			expect('"');
			StringBuilder b = new StringBuilder();
			while (true) {
				char c = s.charAt(i++);
				if (c == '"') return b.toString();
				if (c != '\\') {
					b.append(c);
					continue;
				}
				char e = s.charAt(i++);
				switch (e) {
					case 'n' -> b.append('\n');
					case 't' -> b.append('\t');
					case 'r' -> b.append('\r');
					case 'b' -> b.append('\b');
					case 'f' -> b.append('\f');
					case 'u' -> {
						b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> b.append(e);
				}
			}
		}

		static String quote(String v) {
			StringBuilder b = new StringBuilder("\"");
			for (char c : v.toCharArray()) {
				switch (c) {
					case '"' -> b.append("\\\"");
					case '\\' -> b.append("\\\\");
					case '\n' -> b.append("\\n");
					case '\r' -> b.append("\\r");
					case '\t' -> b.append("\\t");
					default -> {
						if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
						else b.append(c);
					}
				}
			}
			return b.append('"').toString();
		}

		@SuppressWarnings("unchecked")
		static String write(Object v, int indent) {
			String pad = "  ".repeat(indent + 1), end = "  ".repeat(indent);
			if (v == null) return "null";
			if (v instanceof String str) return quote(str);
			if (v instanceof Boolean || v instanceof Number || v instanceof NumWrap) return v.toString();
			if (v instanceof Map<?, ?> m) {
				if (m.isEmpty()) return "{}";
				StringBuilder b = new StringBuilder("{\n");
				int n = 0;
				for (var e : ((Map<String, Object>) m).entrySet()) {
					b.append(pad).append(quote(e.getKey())).append(" : ").append(write(e.getValue(), indent + 1));
					b.append(++n < m.size() ? ",\n" : "\n");
				}
				return b.append(end).append('}').toString();
			}
			List<Object> l = (List<Object>) v;
			if (l.isEmpty()) return "[]";
			StringBuilder b = new StringBuilder("[\n");
			for (int k = 0; k < l.size(); k++) b.append(pad).append(write(l.get(k), indent + 1)).append(k + 1 < l.size() ? ",\n" : "\n");
			return b.append(end).append(']').toString();
		}
	}

	/** Non-integer numbers, kept exactly as written. */
	record NumWrap(String text) {
		@Override
		public String toString() {
			return text;
		}
	}

}
