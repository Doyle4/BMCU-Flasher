package com.pjarczak.bmcuflasher

object FirmwareSelector {
  const val FORCE_STD = "standard"
  const val FORCE_SOFT = "soft"
  const val FORCE_HF = "high_force"

  const val SLOT_AUTO = "AMS_AUTO"
  const val SLOT_A = "AMS_A"
  const val SLOT_B = "AMS_B"
  const val SLOT_C = "AMS_C"
  const val SLOT_D = "AMS_D"

  val RETRACTS = listOf(
    "9.5cm" to "0.095f",
    "10cm" to "0.10f",
    "20cm" to "0.20f",
    "25cm" to "0.25f",
    "30cm" to "0.30f",
    "35cm" to "0.35f",
    "40cm" to "0.40f",
    "45cm" to "0.45f",
    "50cm" to "0.50f",
    "55cm" to "0.55f",
    "60cm" to "0.60f",
    "65cm" to "0.65f",
    "70cm" to "0.70f",
    "75cm" to "0.75f",
    "80cm" to "0.80f",
    "85cm" to "0.85f",
    "90cm" to "0.90f"
  )

  data class Sel(val relPath: String, val display: String)

  fun retractValues(): List<String> {
    return RETRACTS.map { it.first }
  }

  fun build(force: String, slot: String, retractDisp: String, autoload: Boolean, rgb: Boolean): Sel {
    val modeDir = when (force) {
      FORCE_SOFT -> "soft_load(A1)"
      FORCE_HF -> "high_force_load(P1S)"
      else -> "standard(A1)"
    }
    val dmDir = if (autoload) "AUTOLOAD" else "NO_AUTOLOAD"
    val rgbDir = if (rgb) "FILAMENT_RGB_ON" else "FILAMENT_RGB_OFF"
    val retractVal = RETRACTS.first { it.first == retractDisp }.second

    val slotDir: String
    val fileSlot: String
    val fileName: String
    val retCm: String

    when (slot) {
      SLOT_AUTO -> {
        slotDir = SLOT_AUTO
        fileSlot = SLOT_AUTO
        fileName = "ams_auto_${retractVal}.bin"
        retCm = retractDisp
      }
      else -> {
        slotDir = slot
        fileSlot = slot
        val suffix = slot.substringAfter("_").lowercase()
        fileName = "ams_${suffix}_${retractVal}.bin"
        retCm = retractDisp
      }
    }

    val rel = "${modeDir}/${dmDir}/${rgbDir}/${slotDir}/${fileName}"
    val disp = "[ONLINE] Bambu ${fileSlot} RET=${retCm} AUTOLOAD=${if (autoload) "ON" else "OFF"} RGB=${if (rgb) "ON" else "OFF"} (${modeDir})"
    return Sel(rel, disp)
  }
}
