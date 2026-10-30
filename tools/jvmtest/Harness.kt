// Arnés de prueba en JVM para el núcleo del juego (sin Android).
// Compilar: ver tools/run_jvm_tests.sh
import com.guileman.snipervr.core.*
import com.guileman.snipervr.game.*
import java.io.File
import kotlin.math.abs

var failures = 0
fun check(name: String, cond: Boolean, info: String = "") {
    println("  [${if (cond) "OK" else "FALLA"}] $name $info")
    if (!cond) failures++
}

fun main(args: Array<String>) {
    val out = StringBuilder("# name,dist,elev_mrad\n")
    println("== Balística")
    for ((name, idx) in listOf(".308" to 0, "6.5CM" to 1)) {
        val s = BallisticSolver(Cartridge.PRESETS[idx]).apply { enableCoriolis = false; enableSpinDrift = false }
        val z = s.zeroAngle(100.0, 0.045)
        for (d in listOf(300.0, 600.0, 1000.0)) {
            val sol = s.solution(d, z, 0.045, 0.0, 0.0)
            out.append("$name,$d,${sol.elevationMil}\n")
            println("  $name $d m -> ${"%.3f".format(sol.elevationMil)} mrad, ToF ${"%.3f".format(sol.timeOfFlight)} s")
        }
    }
    File(args.getOrElse(0) { "kotlin_table.csv" }).writeText(out.toString())

    val s = BallisticSolver(Cartridge.PRESETS[0])
    println("  SG .308 = ${"%.2f".format(s.stabilityFactor())}, deriva giro 1.5 s = ${"%.1f".format(s.spinDrift(1.5) * 100)} cm")
    check("Factor de estabilidad razonable", s.stabilityFactor() in 1.2..2.5)

    println("== Proyección de cámara")
    val cam = CameraModel().apply { width = 2000.0; height = 1000.0; focalPx = 1000.0; screenRotationDeg = 90 }
    // Teléfono en landscape mirando al Norte, horizontal: la cámara trasera (-Z dispositivo) debe ser Norte.
    // Dispositivo: X apunta abajo (-Up), Y apunta Este... construimos la rotación desde ejes.
    // Columnas = ejes del dispositivo en el mundo: X -> Arriba, Y -> Oeste, Z -> Sur.
    val r = Mat3(doubleArrayOf(
        0.0, -1.0, 0.0,
        0.0, 0.0, -1.0,
        1.0, 0.0, 0.0))
    cam.orientation = Quat.fromMat3(r)
    cam.update()
    check("forward = Norte", (cam.forward - Vec3.NORTH).length() < 1e-9, "${cam.forward}")
    check("right = Este", (cam.right - Vec3.EAST).length() < 1e-9, "${cam.right}")
    check("up = Arriba", (cam.up - Vec3.UP).length() < 1e-9, "${cam.up}")
    val p = cam.project(cam.position + Vec3(10.0, 100.0, 5.0))!!
    check("Proyección x", abs(p[0] - 1100.0) < 1e-6, "${p[0]}")
    check("Proyección y", abs(p[1] - 450.0) < 1e-6, "${p[1]}")
    val ray = cam.rayFromScreen(1100.0, 450.0)
    check("Rayo inverso", (ray - Vec3(10.0, 100.0, 5.0).normalized()).length() < 1e-9)
    check("Azimut 0", abs(cam.azimuthDeg()) < 1e-6 || abs(cam.azimuthDeg() - 360) < 1e-6)
    check("Cant 0", abs(cam.cantDeg()) < 1e-6, "${cam.cantDeg()}")

    println("== Filtro de orientación")
    val f = OrientationFilter()
    // Dispositivo en la orientación anterior: gravedad medida (arriba en ejes dispositivo) = -X.
    // Campo magnético: Norte con inclinación hacia abajo (hemisferio sur: hacia arriba).
    val rt = r.transpose()
    val upD = rt * Vec3.UP
    val magW = Vec3(0.0, 20.0, 15.0) // Buenos Aires: componente vertical hacia arriba
    val magD = rt * magW
    repeat(20) {
        f.onAccelerometer(upD.x * 9.81, upD.y * 9.81, upD.z * 9.81)
        f.onMagnetometer(magD.x, magD.y, magD.z)
    }
    val fw = f.q.rotate(Vec3(0.0, 0.0, -1.0))
    check("Fusión: cámara mira al Norte", (fw - Vec3.NORTH).length() < 1e-3, "$fw")
    // Giro de 30° a la derecha (alrededor del eje Up del mundo = -X del dispositivo... en ejes dispositivo)
    var ts = 1_000_000_000L
    f.onGyroscope(0.0, 0.0, 0.0, ts)
    val axisD = rt * Vec3.UP // eje vertical en ejes del dispositivo
    val rate = -Math.toRadians(30.0) // negativo = horario visto desde arriba = hacia el Este
    repeat(100) {
        ts += 10_000_000L
        f.onGyroscope(axisD.x * rate, axisD.y * rate, axisD.z * rate, ts)
    }
    cam.orientation = f.q; cam.update()
    println("  azimut tras girar 30° (con corrección de brújula parcial): ${"%.1f".format(cam.azimuthDeg())}")
    check("Giróscopo integra el giro", cam.azimuthDeg() in 20.0..31.0)

    println("== Colisión segmento-caja")
    val parts = Models.build(TargetKind.HUMAN, 0)
    val boxes = Models.toWorld(parts, Vec3(0.0, 300.0, 0.0), Math.PI, 0.0, 0.0, 0.0)
    val head = boxes.filter { it.zone == Zone.HEAD }.maxByOrNull { it.size() }!!
    val hit = Models.segmentHitsBox(Vec3(0.0, 0.0, 1.62), Vec3(0.0, 400.0, 1.62), head)
    check("Disparo a la cabeza impacta", hit != null)
    val miss = Models.segmentHitsBox(Vec3(0.0, 0.0, 2.2), Vec3(0.0, 400.0, 2.2), head)
    check("Disparo alto no impacta", miss == null)

    println("== Mundo: placa de acero a 400 m con corrección de torreta")
    val w = World(GameSettings(targetSet = TargetSet.PRACTICE, maxWind = 0.0), seed = 42)
    w.wind.baseSpeed = 0.0
    w.spawnInitial(0.0)
    val plate = w.targets.minByOrNull { abs(it.pos.length() - 400.0) }!!
    val az = Math.toDegrees(kotlin.math.atan2(plate.pos.x, plate.pos.y))
    w.update(0.016)
    // Apuntar al centro de la placa
    val camW = CameraModel()
    val aimPoint = plate.pos + Vec3(0.0, 0.0, 0.95)
    val fwd = (aimPoint - camW.position).normalized()
    val rightW = (fwd cross Vec3.UP).normalized()
    val upW = rightW cross fwd
    // Columnas: X_d -> up, Y_d -> -right, Z_d -> -fwd
    camW.orientation = Quat.fromMat3(Mat3(doubleArrayOf(
        upW.x, -rightW.x, -fwd.x,
        upW.y, -rightW.y, -fwd.y,
        upW.z, -rightW.z, -fwd.z)))
    camW.update()
    val range = w.rangeUnderReticle(camW)
    println("  telémetro: ${"%.1f".format(range ?: -1.0)} m  (azimut placa ${"%.1f".format(az)}°)")
    check("Telémetro mide la placa", range != null && abs(range - 400) < 3)
    // Sin corrección: debería pegar bajo (o errar)
    val sol = w.solver.solution(range!!, w.zeroAngle, w.settings.sightHeight, 0.0, 0.0)
    w.elevClicks = Math.round(sol.elevationRad / w.settings.turretUnit.clickRad).toInt()
    println("  solución: ${"%.2f".format(sol.elevationMil)} mrad -> ${w.elevClicks} clics")
    w.fire(camW)
    repeat(200) { w.update(0.01) }
    val steelHit = w.events.any { it is GameEvent.Steel }
    check("Impacto en la placa con la corrección", steelHit, w.events.toString())

    println("== Mundo: blancos humanos se mueven")
    val w2 = World(GameSettings(targetSet = TargetSet.HUMANS), seed = 7)
    w2.spawnInitial(90.0)
    val p0 = w2.targets.map { it.pos }
    repeat(300) { w2.update(0.016) }
    val moved = w2.targets.zip(p0).count { (t, q) -> (t.pos - q).length() > 0.5 }
    check("Los blancos caminan", moved >= 2, "($moved movidos)")

    println(if (failures == 0) "\nRESULTADO: TODO OK" else "\nRESULTADO: $failures FALLAS")
    if (failures > 0) kotlin.system.exitProcess(1)
}

fun WorldBox.size() = half.x * half.y * half.z
