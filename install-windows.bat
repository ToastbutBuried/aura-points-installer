@echo off
rem Aura Points server installer - double-click me. Runs install.ps1 from the repo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main/install.ps1 | iex"
