package com.pjarczak.bmcuflasher

object FirmwareSelector {
  const val FORCE_STD = "Standard (normal load force)"
  const val FORCE_HF = "High force (stronger load/print pressure)"

  const val SLOT_SOLO = "SOLO"
  const val SLOT_A = "AMS_A"
  const val SLOT_B = "AMS_B"
  const val SLOT_C = "AMS_C"
  const val SLOT_D = "AMS_D"

  val RETRACTS = listOf(
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

  fun retractValuesForSlot(slot: String): List<String> {
    return if (slot == SLOT_SOLO) listOf("9.5cm") + RETRACTS.map { it.first } else RETRACTS.map { it.first }
  }

  fun build(force: String, slot: String, retractDisp: String, autoload: Boolean, rgb: Boolean): Sel {
    val modeDir = if (force == FORCE_HF) "high_force_load(P1S)" else "standard(A1)"
    val dmDir = if (autoload) "AUTOLOAD" else "NO_AUTOLOAD"
    val rgbDir = if (rgb) "FILAMENT_RGB_ON" else "FILAMENT_RGB_OFF"

    val retractVal = if (retractDisp == "9.5cm") "0.095f" else RETRACTS.first { it.first == retractDisp }.second

    var slotDir = slot
    var fileSlot = slot
    val fileName: String
    val retCm: String

    if (slot == SLOT_SOLO) {
      if (retractVal == "0.095f") {
        slotDir = "SOLO"
        fileSlot = "SOLO"
        fileName = "solo_0.095f.bin"
        retCm = "9.5cm"
      } else {
        slotDir = "AMS_A"
        fileSlot = "AMS_A"
        fileName = "ams_a_${retractVal}.bin"
        retCm = retractDisp
      }
    } else {
      slotDir = slot
      val s = slot.substringAfter("_").lowercase()
      fileName = "ams_${s}_${retractVal}.bin"
      retCm = retractDisp
    }

    val rel = "${modeDir}/${dmDir}/${rgbDir}/${slotDir}/${fileName}"
    val disp = "[ONLINE] ${fileSlot} RET=${retCm} AUTOLOAD=${if (autoload) "ON" else "OFF"} RGB=${if (rgb) "ON" else "OFF"} (${modeDir})"
    return Sel(rel, disp)
  }
}
