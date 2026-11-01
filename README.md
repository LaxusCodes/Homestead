# Homestead

Cozy "Vanilla+" survival for Minecraft servers: a **Paper plugin** plus a **resource pack** (Farmer's Delight-style models, realistic HUD, custom art).

[![build](https://github.com/LaxusCodes/Homestead/actions/workflows/build.yml/badge.svg)](https://github.com/LaxusCodes/Homestead/actions/workflows/build.yml)

## Repo layout
| Path | What it is |
|---|---|
| `Homestead-Plugin-src/homestead/` | Maven plugin source (Java 21, Paper API 1.21.1). See its [README](Homestead-Plugin-src/homestead/README.md) |
| `Realism-ResourcePack-Homestead-v6/` | Unzipped resource pack (`pack.mcmeta` at the folder root) |

## No local compiling needed — GitHub builds it
Any push to `main` runs [`.github/workflows/build.yml`](.github/workflows/build.yml) and refreshes a rolling **latest release** with the plain jar attached:

**<https://github.com/LaxusCodes/Homestead/releases/download/latest/Homestead.jar>**

Release assets are raw files, no zip wrapper (the same approach the Pumpkin server uses for its binaries). If you'd rather use Actions: the run's **Homestead-0.1.\<run number\>** artifact contains the same jar, but GitHub always wraps artifact downloads in a zip. `/version Homestead` shows the bumped build version (one bump per run).

Push a tag like `v0.1.1` and the workflow additionally creates a **release** with `Homestead.jar` + the zipped resource pack ready for download.

```bash
git tag v0.1.1
git push origin v0.1.1
```

## Local build (optional)
```bash
cd Homestead-Plugin-src/homestead
mvn clean package
```

## Server-side setup
- Paper 1.21.1+ (ViaVersion works for mixed client versions)
- `Homestead.jar` in `plugins/`
- Host `Realism-ResourcePack-Homestead-v6.zip` on any HTTPS host and set in `server.properties`:
  ```properties
  resource-pack=<url>
  resource-pack-sha1=<sha1 of the zip>
  ```

## Resource pack regeneration
Only needed if you change `cmd`/`model`/HUD values in `config.yml`:
```bash
cd Homestead-Plugin-src/homestead
pip install pyyaml pillow
python tools/gen_pack.py src/main/resources/config.yml ../../Realism-ResourcePack-Homestead-v6
python tools/make_assets.py ../../Realism-ResourcePack-Homestead-v6 src/main/resources/config.yml
```
Re-zip with `pack.mcmeta` at the zip root, update the SHA-1.
