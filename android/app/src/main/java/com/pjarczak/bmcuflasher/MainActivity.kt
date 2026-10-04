package com.pjarczak.bmcuflasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.pjarczak.bmcuflasher.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
  private lateinit var b: ActivityMainBinding
  private lateinit var usb: UsbManager
  private lateinit var i18n: I18n

  private val ACTION_USB_PERMISSION = "com.pjarczak.bmcuflasher.USB_PERMISSION"
  private val lock = Object()
  private var permDevId: Int = -1
  private var permGranted: Boolean? = null

  private data class DevItem(val dev: UsbDevice, val label: String)
  private data class AdapterPreset(val label: String, val vid: Int, val pid: Int, val baud: Int, val fastBaud: Int, val noFast: Boolean)

  private val devs = ArrayList<DevItem>()
  private var flashing = false
  private var localFirmwareUri: Uri? = null
  private var localFirmwareName: String = ""

  private val LOG_MAX_CHARS = 200_000
  private val LOG_TRIM_CHARS = 60_000

  private val APP_URL = "https://github.com/jarczakpawel/BMCU-Flasher"
  private val FW_URL = "https://github.com/jarczakpawel/BMCU-C-PJARCZAK"
  private val KLIPPER_URL = "https://github.com/jarczakpawel/BMCU-Klipper"

  private val modeIds = listOf("usb", "native_usb", "ttl")
  private val sourceIds = listOf("online", "local")
  private val productIds = listOf("bambu", "klipper")
  private val forceIds = listOf(FirmwareSelector.FORCE_STD, FirmwareSelector.FORCE_SOFT, FirmwareSelector.FORCE_HF)
  private val slotIds = listOf(
    FirmwareSelector.SLOT_AUTO,
    FirmwareSelector.SLOT_A,
    FirmwareSelector.SLOT_B,
    FirmwareSelector.SLOT_C,
    FirmwareSelector.SLOT_D
  )

  private val ttlAdapters = listOf(
    AdapterPreset("CH340/CH341 (WCH)", 0x1A86, 0x7523, 115200, 1_000_000, false),
    AdapterPreset("CP2102/CP210x (Silabs)", 0x10C4, 0xEA60, 115200, 921_600, false),
    AdapterPreset("FT232R (FTDI)", 0x0403, 0x6001, 115200, 1_000_000, false),
    AdapterPreset("PL2303 (Prolific)", 0x067B, 0x2303, 115200, 460_800, false),
    AdapterPreset("CH9102 (WCH)", 0x1A86, 0x55D4, 115200, 1_000_000, false),
    AdapterPreset("CH343 (WCH)", 0x1A86, 0x55D3, 115200, 1_000_000, false),
    AdapterPreset("ALL", 0, 0, 115200, 1_000_000, false)
  )

  private val localFirmwarePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } catch (_: Throwable) {}
      localFirmwareUri = uri
      localFirmwareName = queryDisplayName(uri) ?: (uri.lastPathSegment ?: "firmware.bin")
      b.txtLocalFirmware.text = i18n.t("android_local_selected").replace("{name}", localFirmwareName)
    }
  }

  private val rx = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      when (intent.action) {
        ACTION_USB_PERMISSION -> {
          val d = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
          }
          val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
          if (d != null) {
            synchronized(lock) {
              permDevId = d.deviceId
              permGranted = ok
              lock.notifyAll()
            }
          }
        }
        UsbManager.ACTION_USB_DEVICE_DETACHED,
        UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    b = ActivityMainBinding.inflate(layoutInflater)
    setContentView(b.root)

    usb = getSystemService(Context.USB_SERVICE) as UsbManager
    i18n = I18n(this)

    applyTexts()
    setupSelectors()

    b.txtLinks.text = "App: $APP_URL\nBambu: $FW_URL\nKlipper: $KLIPPER_URL"
    Linkify.addLinks(b.txtLinks, Linkify.WEB_URLS)
    b.txtLinks.movementMethod = LinkMovementMethod.getInstance()
    b.txtLog.setText("", android.widget.TextView.BufferType.EDITABLE)

    b.btnRefresh.setOnClickListener { refreshDevices() }
    b.btnFlash.setOnClickListener { startFlash() }
    b.btnLocalFirmware.setOnClickListener { localFirmwarePicker.launch(arrayOf("application/octet-stream", "application/x-binary", "*/*")) }

    val f = IntentFilter().apply {
      addAction(ACTION_USB_PERMISSION)
      addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
      addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)

    applyModeUi()
    applyFirmwareUi()
    refreshDevices()
    fetchVersions()
  }

  override fun onDestroy() {
    try { unregisterReceiver(rx) } catch (_: Throwable) {}
    super.onDestroy()
  }

  private fun applyTexts() {
    b.txtTitle.text = i18n.t("android_title")
    b.txtDeviceLabel.text = i18n.t("android_device_label")
    b.txtWarnTitle.text = i18n.t("warn_title")
    b.txtWarnText.text = i18n.t("warn_text")
    b.txtModeLabel.text = i18n.t("mode_label")
    b.txtAdapterLabel.text = i18n.t("adapter_label")
    b.btnRefresh.text = i18n.t("android_refresh")
    b.btnFlash.text = i18n.t("android_flash")
    b.txtOnlineTitle.text = i18n.t("android_firmware_title")
    b.txtFirmwareSourceLabel.text = i18n.t("android_firmware_source_label")
    b.txtProductLabel.text = i18n.t("android_product_label")
    b.btnLocalFirmware.text = i18n.t("android_local_select")
    b.txtLocalFirmware.text = i18n.t("android_local_none")
    b.txtKlipperInfo.text = i18n.t("android_klipper_desc")
    b.txtVersions.text = i18n.t("android_versions_loading")
    b.txtForceLabel.text = i18n.t("online_force_label")
    b.txtSlotLabel.text = i18n.t("online_slot_label")
    b.txtRetractLabel.text = i18n.t("online_retract_label")
    b.chkAutoload.text = i18n.t("online_autoload")
    b.chkRgb.text = i18n.t("online_rgb")
    b.chkNoFast.text = i18n.t("no_fast")
    b.txtLogTitle.text = i18n.t("android_log_title")
    b.txtTtlHint.text = i18n.t("ttl_hint_boot_reset")
    updateDeviceHint()
    updateForceDesc()
  }

  private fun setupSelectors() {
    b.spnMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(i18n.t("mode_usb"), i18n.t("mode_native_usb"), i18n.t("mode_ttl")))
    b.spnMode.setSelection(0)
    b.spnMode.onItemSelected {
      applyModeUi()
      refreshDevices()
    }

    b.spnAdapter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ttlAdapters.map { it.label })
    b.spnAdapter.setSelection(0)
    b.spnAdapter.onItemSelected {
      applyAdapterPreset()
      if (currentModeId() == "ttl") refreshDevices()
    }

    b.spnFirmwareSource.adapter = ArrayAdapter(
      this,
      android.R.layout.simple_spinner_dropdown_item,
      listOf(i18n.t("android_source_online"), i18n.t("android_source_local"))
    )
    b.spnFirmwareSource.setSelection(0)
    b.spnFirmwareSource.onItemSelected { applyFirmwareUi() }

    b.spnProduct.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Bambu", "Klipper"))
    b.spnProduct.setSelection(0)
    b.spnProduct.onItemSelected { applyFirmwareUi() }

    b.spnForce.adapter = ArrayAdapter(
      this,
      android.R.layout.simple_spinner_dropdown_item,
      listOf(i18n.t("online_force_std"), i18n.t("online_force_soft"), i18n.t("online_force_hf"))
    )
    b.spnForce.setSelection(0)
    b.spnForce.onItemSelected { updateForceDesc() }

    b.spnSlot.adapter = ArrayAdapter(
      this,
      android.R.layout.simple_spinner_dropdown_item,
      listOf(i18n.t("online_slot_auto"), FirmwareSelector.SLOT_A, FirmwareSelector.SLOT_B, FirmwareSelector.SLOT_C, FirmwareSelector.SLOT_D)
    )
    b.spnSlot.setSelection(0)
    updateRetracts()
    b.spnSlot.onItemSelected { updateRetracts() }
  }

  private fun currentModeId(): String = modeIds.getOrElse(b.spnMode.selectedItemPosition) { "usb" }
  private fun currentSourceId(): String = sourceIds.getOrElse(b.spnFirmwareSource.selectedItemPosition) { "online" }
  private fun currentProductId(): String = productIds.getOrElse(b.spnProduct.selectedItemPosition) { "bambu" }
  private fun currentForceId(): String = forceIds.getOrElse(b.spnForce.selectedItemPosition) { FirmwareSelector.FORCE_STD }
  private fun currentSlotId(): String = slotIds.getOrElse(b.spnSlot.selectedItemPosition) { FirmwareSelector.SLOT_AUTO }

  private fun selectedAdapter(): AdapterPreset {
    val idx = b.spnAdapter.selectedItemPosition
    return if (idx in ttlAdapters.indices) ttlAdapters[idx] else ttlAdapters[0]
  }

  private fun applyAdapterPreset() {
    val preset = selectedAdapter()
    b.chkNoFast.isChecked = preset.noFast
  }

  private fun updateForceDesc() {
    val desc = when (currentForceId()) {
      FirmwareSelector.FORCE_SOFT -> i18n.t("online_force_soft_desc")
      FirmwareSelector.FORCE_HF -> i18n.t("online_force_hf_desc")
      else -> i18n.t("online_force_std_desc")
    }
    b.txtForceDesc.text = desc
  }

  private fun updateRetracts() {
    val vals = FirmwareSelector.retractValues()
    b.spnRetract.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vals)
    b.spnRetract.setSelection(0)
  }

  private fun applyModeUi() {
    val ttl = currentModeId() == "ttl"
    b.rowAdapter.visibility = if (ttl) View.VISIBLE else View.GONE
    if (!ttl) showTtlHint(false)
    updateDeviceHint()
  }

  private fun applyFirmwareUi() {
    val online = currentSourceId() == "online"
    val bambu = currentProductId() == "bambu"
    b.rowLocalFirmware.visibility = if (online) View.GONE else View.VISIBLE
    b.rowProduct.visibility = if (online) View.VISIBLE else View.GONE
    b.rowBambuOptions.visibility = if (online && bambu) View.VISIBLE else View.GONE
    b.txtKlipperInfo.visibility = if (online && !bambu) View.VISIBLE else View.GONE
  }

  private fun updateDeviceHint() {
    b.txtDeviceHint.text = when (currentModeId()) {
      "ttl" -> i18n.t("android_device_hint_ttl")
      "native_usb" -> i18n.t("android_device_hint_native_usb")
      else -> i18n.t("android_device_hint_usb")
    }
  }

  private fun fetchVersions() {
    thread(start = true, isDaemon = true) {
      val bambu = try { RemoteFirmware.getVersion() } catch (_: Throwable) { "?" }
      val klipper = try { RemoteFirmware.getKlipperVersion() } catch (_: Throwable) { "?" }
      runOnUiThread {
        b.txtVersions.text = i18n.t("android_versions")
          .replace("{bambu}", bambu)
          .replace("{klipper}", klipper)
      }
    }
  }

  private fun queryDisplayName(uri: Uri): String? {
    return try {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    } catch (_: Throwable) { null }
  }

  private fun refreshDevices() {
    devs.clear()
    val prober = UsbSerialProber.getDefaultProber()
    val mode = currentModeId()

    for (d in usb.deviceList.values) {
      when (mode) {
        "usb" -> {
          if (d.vendorId == 0x1A86 && d.productId == 0x7523) {
            devs.add(DevItem(d, "CH340 ${d.deviceName}"))
          }
        }
        "native_usb" -> {
          if ((d.vendorId == 0x4348 || d.vendorId == 0x1A86) && d.productId == 0x55E0) {
            val name = d.productName?.takeIf { it.isNotBlank() } ?: "WCH ISP"
            devs.add(DevItem(d, "$name ${d.deviceName} (vid=0x%04X pid=0x%04X)".format(d.vendorId, d.productId)))
          }
        }
        else -> {
          if (prober.probeDevice(d) == null) continue
          val preset = selectedAdapter()
          if (preset.vid != 0 || preset.pid != 0) {
            if (d.vendorId != preset.vid || d.productId != preset.pid) continue
          }
          val name = d.productName?.takeIf { it.isNotBlank() } ?: d.deviceName
          val label = "$name (vid=0x%04X pid=0x%04X)".format(d.vendorId, d.productId)
          devs.add(DevItem(d, label))
        }
      }
    }

    val labels = if (devs.isEmpty()) listOf("-") else devs.map { it.label }
    b.spnDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    if (devs.isNotEmpty()) b.spnDevice.setSelection(0)
  }

  private fun log(level: String, msg: String) {
    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    runOnUiThread {
      val e = b.txtLog.editableText
      e.append(ts)
      e.append('\t')
      e.append(level)
      e.append('\t')
      e.append(msg)
      e.append('\n')
      if (e.length > LOG_MAX_CHARS) {
        val del = LOG_TRIM_CHARS.coerceAtMost(e.length)
        e.delete(0, del)
      }

      if (currentModeId() == "ttl") {
        if (level == "ACTION" && msg.contains("enter bootloader now")) showTtlHint(true)
        if (msg.contains("bootloader detected")) showTtlHint(false)
      }
    }
  }

  private fun showTtlHint(show: Boolean) {
    b.txtTtlHint.visibility = if (show && currentModeId() == "ttl") View.VISIBLE else View.GONE
  }

  private fun prog(pct: Int) {
    runOnUiThread { b.pbar.progress = pct.coerceIn(0, 100) }
  }

  private fun pickDevice(): UsbDevice? {
    if (devs.isEmpty()) return null
    val idx = b.spnDevice.selectedItemPosition
    if (idx !in devs.indices) return devs[0].dev
    return devs[idx].dev
  }

  private fun ensurePermission(dev: UsbDevice, timeoutMs: Long = 15000): Boolean {
    if (usb.hasPermission(dev)) return true

    val flags = if (Build.VERSION.SDK_INT >= 31) {
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)

    synchronized(lock) {
      permDevId = dev.deviceId
      permGranted = null
      usb.requestPermission(dev, pi)

      val end = System.currentTimeMillis() + timeoutMs
      while (permGranted == null && System.currentTimeMillis() < end) {
        try { lock.wait(200) } catch (_: Throwable) {}
      }
      val ok = permGranted == true && permDevId == dev.deviceId
      permGranted = null
      return ok
    }
  }

  private fun confirmSafety(onOk: () -> Unit) {
    val msg = if (currentModeId() == "native_usb") i18n.t("android_native_safety") else i18n.t("warn_text")
    AlertDialog.Builder(this)
      .setTitle(i18n.t("warn_title"))
      .setMessage(msg)
      .setPositiveButton(i18n.t("ok")) { _d, _w -> onOk() }
      .setNegativeButton(i18n.t("cancel")) { _d, _w -> }
      .show()
  }

  private fun startFlash() {
    if (flashing) {
      Toast.makeText(this, i18n.t("android_busy"), Toast.LENGTH_SHORT).show()
      return
    }

    if (currentSourceId() == "local") {
      val uri = localFirmwareUri
      if (uri == null) {
        Toast.makeText(this, i18n.t("err_select_bin"), Toast.LENGTH_LONG).show()
        return
      }
      if (localFirmwareName.isNotBlank() && !localFirmwareName.lowercase().endsWith(".bin")) {
        Toast.makeText(this, i18n.t("err_bin_ext"), Toast.LENGTH_LONG).show()
        return
      }
    }

    val dev = pickDevice()
    if (dev == null) {
      val msg = when (currentModeId()) {
        "ttl" -> i18n.t("android_no_device_ttl")
        "native_usb" -> i18n.t("android_no_device_native_usb")
        else -> i18n.t("android_no_device_usb")
      }
      Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
      return
    }

    confirmSafety { startFlashImpl(dev) }
  }

  private fun openNativeTransport(): BmcuFlasher.NativeUsbTransport? {
    val dev = usb.deviceList.values.firstOrNull {
      (it.vendorId == 0x4348 || it.vendorId == 0x1A86) && it.productId == 0x55E0
    } ?: return null
    if (!ensurePermission(dev)) throw RuntimeException(i18n.t("android_perm_denied"))
    val conn = usb.openDevice(dev) ?: throw RuntimeException(i18n.t("android_open_fail"))
    return try {
      BmcuFlasher.NativeUsbTransport(dev, conn)
    } catch (e: Throwable) {
      try { conn.close() } catch (_: Throwable) {}
      throw e
    }
  }

  private fun startFlashImpl(dev: UsbDevice) {
    if (flashing) return

    val mode = currentModeId()
    val source = currentSourceId()
    val product = currentProductId()
    val force = currentForceId()
    val slot = currentSlotId()
    val retract = b.spnRetract.selectedItem as? String ?: "9.5cm"
    val autoload = b.chkAutoload.isChecked
    val rgb = b.chkRgb.isChecked
    val preset = if (mode == "ttl") selectedAdapter() else ttlAdapters[0]
    val noFast = b.chkNoFast.isChecked || preset.noFast
    val localUri = localFirmwareUri
    val localName = localFirmwareName

    flashing = true
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    b.btnFlash.isEnabled = false
    b.pbar.progress = 0
    showTtlHint(false)

    thread(start = true, isDaemon = true) {
      var conn: android.hardware.usb.UsbDeviceConnection? = null
      var port: UsbSerialPort? = null
      var onlineFile: File? = null

      try {
        if (!ensurePermission(dev)) throw RuntimeException(i18n.t("android_perm_denied"))

        val fwBytes: ByteArray = if (source == "local") {
          val uri = localUri ?: throw RuntimeException(i18n.t("err_select_bin"))
          log("INFO", "local: selected ${localName.ifBlank { uri.toString() }}")
          contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw RuntimeException(i18n.t("err_select_bin"))
        } else {
          val fwCache = File(cacheDir, "firmwares")
          val pair = if (product == "klipper") {
            log("INFO", "online: selected Klipper firmware")
            RemoteFirmware.downloadKlipper(fwCache, ::log) { p -> prog(p) }
          } else {
            val sel = FirmwareSelector.build(force, slot, retract, autoload, rgb)
            log("INFO", "online: selected Bambu ${sel.relPath}")
            RemoteFirmware.download(sel.relPath, fwCache, ::log) { p -> prog(p) }
          }
          onlineFile = pair.first
          log("INFO", "online: using ${pair.second} (${pair.first.name})")
          prog(0)
          pair.first.readBytes()
        }

        if (fwBytes.isEmpty()) throw RuntimeException("firmware file is empty")

        if (mode == "native_usb") {
          log("INFO", "open native USB mode")
          BmcuFlasher.flashNativeUsb(
            firmware = fwBytes,
            log = ::log,
            progress = ::prog,
            openTransport = { openNativeTransport() },
            verify = true
          )
        } else {
          val prober = UsbSerialProber.getDefaultProber()
          val driver = prober.probeDevice(dev) ?: throw RuntimeException(i18n.t("android_probe_fail"))
          conn = usb.openDevice(dev) ?: throw RuntimeException(i18n.t("android_open_fail"))
          port = driver.ports[0]

          port!!.open(conn)
          port!!.setParameters(preset.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
          if (mode == "usb") {
            try { port!!.setDTR(true); port!!.setRTS(true) } catch (_: Throwable) {}
          }

          log("INFO", "open port=${dev.deviceName} mode=$mode")
          if (mode == "usb") {
            BmcuFlasher.flashUsb(
              port = port!!,
              firmware = fwBytes,
              log = ::log,
              progress = ::prog,
              baud = preset.baud,
              fastBaud = preset.fastBaud,
              noFast = noFast,
              verify = true
            )
          } else {
            BmcuFlasher.flashTtl(
              port = port!!,
              firmware = fwBytes,
              log = ::log,
              progress = ::prog,
              baud = preset.baud,
              fastBaud = preset.fastBaud,
              noFast = noFast,
              verify = true
            )
          }
        }

        try {
          onlineFile?.let {
            if (it.isFile) {
              it.delete()
              log("INFO", "online: cache removed (${it.name})")
            }
          }
        } catch (_: Throwable) {}

        runOnUiThread { Toast.makeText(this, i18n.t("android_done"), Toast.LENGTH_SHORT).show() }
      } catch (e: Throwable) {
        log("ERROR", e.message ?: e.toString())
        runOnUiThread { Toast.makeText(this, e.message ?: i18n.t("err_generic"), Toast.LENGTH_LONG).show() }
      } finally {
        if (mode == "usb") {
          try { port?.setDTR(true); port?.setRTS(true) } catch (_: Throwable) {}
        }
        try { port?.close() } catch (_: Throwable) {}
        try { conn?.close() } catch (_: Throwable) {}

        runOnUiThread {
          b.btnFlash.isEnabled = true
          showTtlHint(false)
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        flashing = false
      }
    }
  }

  private fun android.widget.Spinner.onItemSelected(fn: () -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
        fn()
      }

      override fun onNothingSelected(parent: AdapterView<*>) {}
    }
  }
}
