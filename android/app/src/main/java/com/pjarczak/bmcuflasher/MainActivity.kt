package com.pjarczak.bmcuflasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
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

  private val devs = ArrayList<DevItem>()
  private var flashing = false

  private val LOG_MAX_CHARS = 200_000
  private val LOG_TRIM_CHARS = 60_000

  private val APP_URL = "https://github.com/jarczakpawel/BMCU-Flasher"
  private val FW_URL = "https://github.com/jarczakpawel/BMCU-C-PJARCZAK"

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
        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
          refreshDevices()
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    b = ActivityMainBinding.inflate(layoutInflater)
    setContentView(b.root)

    usb = getSystemService(Context.USB_SERVICE) as UsbManager
    i18n = I18n(this)

    b.txtTitle.text = i18n.t("android_title")
    b.txtDeviceLabel.text = i18n.t("android_device_label")
    b.txtDeviceHint.text = i18n.t("android_device_hint")
    b.btnRefresh.text = i18n.t("android_refresh")
    b.btnFlash.text = i18n.t("android_flash")

    b.txtWarnTitle.text = i18n.t("warn_title")
    b.txtWarnText.text = i18n.t("warn_text")

    b.txtLinks.text = "App: $APP_URL\nFirmware: $FW_URL"
    Linkify.addLinks(b.txtLinks, Linkify.WEB_URLS)
    b.txtLinks.movementMethod = LinkMovementMethod.getInstance()

    b.spnForce.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(FirmwareSelector.FORCE_STD, FirmwareSelector.FORCE_HF))
    b.spnSlot.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(FirmwareSelector.SLOT_SOLO, FirmwareSelector.SLOT_A, FirmwareSelector.SLOT_B, FirmwareSelector.SLOT_C, FirmwareSelector.SLOT_D))
    b.chkAutoload.text = i18n.t("online_autoload")
    b.chkRgb.text = i18n.t("online_rgb")
    b.chkNoFast.text = i18n.t("no_fast") + " (compatibility)"

    b.txtLog.setText("", android.widget.TextView.BufferType.EDITABLE)

    b.spnSlot.setSelection(0)
    updateRetracts()
    b.spnSlot.onItemSelected { updateRetracts() }

    b.btnRefresh.setOnClickListener { refreshDevices() }
    b.btnFlash.setOnClickListener { startFlash() }

    val f = IntentFilter().apply {
      addAction(ACTION_USB_PERMISSION)
      addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
      addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)

    refreshDevices()
  }

  override fun onDestroy() {
    try { unregisterReceiver(rx) } catch (_: Throwable) {}
    super.onDestroy()
  }

  private fun updateRetracts() {
    val slot = b.spnSlot.selectedItem as String
    val vals = FirmwareSelector.retractValuesForSlot(slot)
    b.spnRetract.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vals)
    b.spnRetract.setSelection(0)
  }

  private fun refreshDevices() {
    devs.clear()
    for (d in usb.deviceList.values) {
      if (d.vendorId == 0x1A86 && d.productId == 0x7523) {
        devs.add(DevItem(d, "CH340 ${d.deviceName}"))
      }
    }
    val labels = devs.map { it.label }
    b.spnDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if (labels.isEmpty()) listOf("(none)") else labels)
    if (labels.isNotEmpty()) b.spnDevice.setSelection(0)
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
    }
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
      val ok = (permGranted == true && permDevId == dev.deviceId)
      permGranted = null
      return ok
    }
  }

  private fun confirmSafety(onOk: () -> Unit) {
    AlertDialog.Builder(this)
      .setTitle(i18n.t("warn_title"))
      .setMessage(i18n.t("warn_text"))
      .setPositiveButton(i18n.t("ok")) { _d, _w -> onOk() }
      .setNegativeButton(i18n.t("cancel")) { _d, _w -> }
      .show()
  }

  private fun startFlash() {
    if (flashing) {
      Toast.makeText(this, i18n.t("android_busy"), Toast.LENGTH_SHORT).show()
      return
    }

    val dev = pickDevice()
    if (dev == null) {
      Toast.makeText(this, i18n.t("android_no_device"), Toast.LENGTH_LONG).show()
      return
    }

    confirmSafety { startFlashImpl(dev) }
  }

  private fun startFlashImpl(dev: UsbDevice) {
    if (flashing) return

    val force = b.spnForce.selectedItem as String
    val slot = b.spnSlot.selectedItem as String
    val retract = b.spnRetract.selectedItem as String
    val autoload = b.chkAutoload.isChecked
    val rgb = b.chkRgb.isChecked
    val noFast = b.chkNoFast.isChecked

    flashing = true
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    b.btnFlash.isEnabled = false
    b.pbar.progress = 0

    thread(start = true, isDaemon = true) {
      var conn: android.hardware.usb.UsbDeviceConnection? = null
      var port: UsbSerialPort? = null

      try {
        if (!ensurePermission(dev)) throw RuntimeException(i18n.t("android_perm_denied"))

        val sel = FirmwareSelector.build(force, slot, retract, autoload, rgb)
        log("INFO", "online: selected ${sel.relPath}")
        prog(0)

        val fwCache = File(cacheDir, "firmwares")
        val (fwFile, ver) = RemoteFirmware.download(sel.relPath, fwCache, ::log) { p -> prog(p) }
        log("INFO", "online: using $ver (${fwFile.name})")
        prog(0)

        val prober = UsbSerialProber.getDefaultProber()
        val driver = prober.probeDevice(dev) ?: throw RuntimeException(i18n.t("android_probe_fail"))
        conn = usb.openDevice(dev) ?: throw RuntimeException(i18n.t("android_open_fail"))
        port = driver.ports[0]

        port!!.open(conn)
        port!!.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try { port!!.setDTR(true); port!!.setRTS(true) } catch (_: Throwable) {}

        val fwBytes = fwFile.readBytes()

        log("INFO", "open port=${dev.deviceName} mode=usb")
        BmcuFlasher.flashUsb(
          port = port!!,
          firmware = fwBytes,
          log = ::log,
          progress = ::prog,
          baud = 115200,
          fastBaud = 1_000_000,
          noFast = noFast,
          verify = true
        )

        try {
          if (fwFile.isFile) {
            fwFile.delete()
            log("INFO", "online: cache removed (${fwFile.name})")
          }
        } catch (_: Throwable) {}

        runOnUiThread { Toast.makeText(this, i18n.t("android_done"), Toast.LENGTH_SHORT).show() }
      } catch (e: Throwable) {
        log("ERROR", e.message ?: e.toString())
        runOnUiThread { Toast.makeText(this, e.message ?: i18n.t("err_generic"), Toast.LENGTH_LONG).show() }
      } finally {
        try { port?.setDTR(true); port?.setRTS(true) } catch (_: Throwable) {}
        try { port?.close() } catch (_: Throwable) {}
        try { conn?.close() } catch (_: Throwable) {}

        runOnUiThread {
          b.btnFlash.isEnabled = true
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
