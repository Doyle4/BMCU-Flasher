package com.pjarczak.bmcuflasher

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.CRC32

object RemoteFirmware {
  const val REMOTE_VERSION_URL = "https://raw.githubusercontent.com/jarczakpawel/BMCU-C-PJARCZAK/refs/heads/main/version"
  const val REMOTE_MANIFEST_URL = "https://raw.githubusercontent.com/jarczakpawel/BMCU-C-PJARCZAK/refs/heads/main/firmwares/manifest.txt"
  const val REMOTE_FIRMWARE_BASE = "https://raw.githubusercontent.com/jarczakpawel/BMCU-C-PJARCZAK/refs/heads/main/firmwares/"
  private const val UA = "BMCUFlasher/1.2"

  data class ManEntry(val sha: String, val crc: String, val size: Long)

  private fun httpGet(url: String, timeoutMs: Int): ByteArray {
    val c = (URL(url).openConnection() as HttpURLConnection)
    c.connectTimeout = timeoutMs
    c.readTimeout = timeoutMs
    c.requestMethod = "GET"
    c.setRequestProperty("User-Agent", UA)
    c.instanceFollowRedirects = true
    c.connect()
    val code = c.responseCode
    if (code !in 200..299) throw RuntimeException("http $code $url")
    return c.inputStream.use { it.readBytes() }
  }

  fun getVersion(timeoutMs: Int = 12000): String {
    val s = httpGet(REMOTE_VERSION_URL, timeoutMs).toString(Charsets.UTF_8).trim()
    if (s.isEmpty()) throw RuntimeException("empty version")
    val parts = s.split(".")
    if (parts.size < 2) throw RuntimeException("bad version: $s")
    val a = parts[0].trim()
    val b = parts[1].trim()
    if (a.any { !it.isDigit() } || b.any { !it.isDigit() }) throw RuntimeException("bad version: $s")
    val major = a.toInt()
    val minorRaw = b.toInt()
    return when {
      minorRaw == 0 -> "V$major"
      (minorRaw % 10) == 0 -> "V$major.${minorRaw / 10}"
      else -> "V$major.$minorRaw"
    }
  }

  fun getManifest(timeoutMs: Int = 20000): Map<String, ManEntry> {
    val txt = httpGet(REMOTE_MANIFEST_URL, timeoutMs).toString(Charsets.UTF_8)
    val out = HashMap<String, ManEntry>()
    for (raw in txt.lineSequence()) {
      val ln = raw.trim()
      if (ln.isEmpty() || ln.startsWith("#")) continue
      val parts = ln.split(Regex("\\s+"), limit = 4)
      if (parts.size != 4) continue
      val sha = parts[0].trim().lowercase()
      val crc = parts[1].trim().uppercase()
      val size = parts[2].trim().toLongOrNull() ?: continue
      val rel = parts[3].trim()
      if (sha.length != 64 || crc.length != 8) continue
      out[rel] = ManEntry(sha, crc, size)
    }
    if (out.isEmpty()) throw RuntimeException("manifest empty")
    return out
  }

  private fun sha256Hex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
  }

  private fun encodePath(relPath: String): String {
    return relPath.split("/").joinToString("/") {
      URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }
  }

  fun download(relPath: String, cacheDir: File, log: (String, String) -> Unit, prog: (Int) -> Unit): Pair<File, String> {
    if (relPath.isEmpty() || relPath.contains("..") || relPath.startsWith("/") || relPath.startsWith("\\")) {
      throw RuntimeException("bad rel_path")
    }

    val ver = getVersion()
    log("INFO", "online: version=$ver")

    val man = getManifest()
    val e = man[relPath] ?: throw RuntimeException("rel_path not in manifest: $relPath")

    cacheDir.mkdirs()
    val out = File(cacheDir, "${e.sha}.bin")
    if (out.isFile) {
      val dig = MessageDigest.getInstance("SHA-256")
      val crc32 = CRC32()
      var size = 0L
      out.inputStream().use { ins ->
        val buf = ByteArray(1024 * 1024)
        while (true) {
          val n = ins.read(buf)
          if (n <= 0) break
          size += n
          dig.update(buf, 0, n)
          crc32.update(buf, 0, n)
        }
      }
      val sha = sha256Hex(dig.digest()).lowercase()
      val crc = "%08X".format(crc32.value.toInt())
      if (sha.lowercase() == e.sha && crc.uppercase() == e.crc && size == e.size) {
        log("INFO", "online: cache hit (${out.name})")
        prog(100)
        return out to ver
      }
    }

    val url = REMOTE_FIRMWARE_BASE + encodePath(relPath)
    log("INFO", "online: download $relPath")
    log("INFO", "online: url $url")

    val tmp = File(cacheDir, "${e.sha}.bin.part")
    if (tmp.isFile) tmp.delete()

    val c = (URL(url).openConnection() as HttpURLConnection)
    c.connectTimeout = 30000
    c.readTimeout = 30000
    c.requestMethod = "GET"
    c.setRequestProperty("User-Agent", UA)
    c.instanceFollowRedirects = true
    c.connect()
    val code = c.responseCode
    if (code !in 200..299) throw RuntimeException("http $code $url")

    val total = (c.getHeaderField("Content-Length")?.toLongOrNull() ?: e.size).coerceAtLeast(1L)
    val dig = MessageDigest.getInstance("SHA-256")
    val crc32 = CRC32()
    var size = 0L
    var lastPct = -1

    c.inputStream.use { ins ->
      FileOutputStream(tmp).use { fos ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          val n = ins.read(buf)
          if (n <= 0) break
          fos.write(buf, 0, n)
          size += n
          dig.update(buf, 0, n)
          crc32.update(buf, 0, n)
          val pct = ((size * 100L) / total).toInt().coerceIn(0, 100)
          if (pct != lastPct) {
            lastPct = pct
            prog(pct)
          }
        }
      }
    }

    val sha = sha256Hex(dig.digest()).lowercase()
    val crc = "%08X".format(crc32.value.toInt()).uppercase()

    if (size != e.size || crc != e.crc || sha != e.sha) {
      tmp.delete()
      throw RuntimeException("online: verify failed size=$size/${e.size} crc=$crc/${e.crc} sha=${sha.take(12)}.../${e.sha.take(12)}...")
    }

    if (out.isFile) out.delete()
    if (!tmp.renameTo(out)) {
      tmp.delete()
      throw RuntimeException("online: rename failed")
    }

    log("INFO", "online: ok size=$size crc=$crc sha=$sha")
    prog(100)
    return out to ver
  }
}
