package com.guileman.snipervr.game

import com.guileman.snipervr.core.Atmosphere
import com.guileman.snipervr.core.BallisticSolver
import com.guileman.snipervr.core.CameraModel
import com.guileman.snipervr.core.Cartridge
import com.guileman.snipervr.core.TrajPoint
import com.guileman.snipervr.core.Trajectory
import com.guileman.snipervr.core.TurretUnit
import com.guileman.snipervr.core.Vec3
import com.guileman.snipervr.core.WindModel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class GameMode { VIRTUAL, REAL }
enum class TargetSet { HUMANS, ANIMALS, PRACTICE }

/** Configuración elegida en el menú. */
data class GameSettings(
    val mode: GameMode = GameMode.VIRTUAL,
    val targetSet: TargetSet = TargetSet.HUMANS,
    val cartridgeIndex: Int = 0,
    val zeroRange: Double = 100.0,
    val sightHeight: Double = 0.045,
    val turretUnit: TurretUnit = TurretUnit.MRAD,
    val magnification: Double = 6.0,
    val eyeDistanceM: Double = 0.30,
    val scopeCircle: Boolean = true,
    val assist: Boolean = true,
    val maxWind: Double = 6.0,
    val useCompass: Boolean = true,
    val syntheticScene: Boolean = false,
    val minRange: Double = 80.0,
    val maxRange: Double = 600.0,
    val temperatureC: Double = 15.0,
    val altitudeM: Double = 0.0
)

sealed class GameEvent {
    data class Fired(val cartridge: String) : GameEvent()
    data class Hit(val label: String, val zone: Zone, val distance: Double, val down: Boolean, val human: Boolean, val worldPos: Vec3?, val delay: Double) : GameEvent()
    data class Impact(val worldPos: Vec3, val distance: Double) : GameEvent()
    data class Steel(val distance: Double, val worldPos: Vec3) : GameEvent()
    data class NearMiss(val label: String) : GameEvent()
    data class RealImpact(val dir: Vec3, val distance: Double) : GameEvent()
}

/** Blanco virtual 3D que se mueve en coordenadas del mundo. */
class VirtualTarget(val id: Int, val kind: TargetKind, variant: Int, var pos: Vec3, var heading: Double) {
    enum class State { WALK, IDLE, RUN, DOWN }
    val parts = Models.build(kind, variant)
    var state = if (kind == TargetKind.STEEL) State.IDLE else State.WALK
    var waypoint = pos
    var timer = 0.0
    var walkPhase = 0.0
    var fall = 0.0
    var wounded = false
    var downTime = 0.0
    var boxes: List<WorldBox> = emptyList()
    var steelSwing = 0.0

    val speed: Double get() = when (state) {
        State.WALK -> kind.walkSpeed * (if (wounded) 0.6 else 1.0)
        State.RUN -> kind.runSpeed * (if (wounded) 0.6 else 1.0)
        else -> 0.0
    }
    val alive get() = state != State.DOWN
}

/** Blanco real detectado por la cámara, anclado a una dirección del mundo. */
class RealTrack(
    var label: String,
    var cocoName: String,
    var dir: Vec3,
    var angW: Double,
    var angH: Double,
    var range: Double,
    var lastSeen: Double
) {
    var hitUntil = 0.0
    var hitText = ""
    val isHuman get() = cocoName == "person"
}

class Shot(val traj: Trajectory, val origin: Vec3, val firedAt: Double) {
    var t = 0.0
    var done = false
    var tracer = ArrayList<Vec3>()
}

class PendingRealShot(val traj: Trajectory, val origin: Vec3, val firedAt: Double, val impactTime: Double, val track: RealTrack?, val impactDir: Vec3, val distance: Double)

/**
 * Mundo del juego: blancos, viento, disparos y balística. No depende de Android (se puede
 * probar en la JVM).
 */
class World(var settings: GameSettings, seed: Long = System.nanoTime()) {
    private val rnd = java.util.Random(seed)
    val cartridge: Cartridge get() = Cartridge.PRESETS[settings.cartridgeIndex.coerceIn(0, Cartridge.PRESETS.size - 1)]
    val solver = BallisticSolver(cartridge, Atmosphere(settings.temperatureC, settings.altitudeM))
    val wind = WindModel(seed).apply { randomize(settings.maxWind) }
    val targets = ArrayList<VirtualTarget>()
    val shots = ArrayList<Shot>()
    val realTracks = ArrayList<RealTrack>()
    private val pendingReal = ArrayList<PendingRealShot>()
    val events = ArrayList<GameEvent>()

    var time = 0.0; private set
    var zeroAngle = solver.zeroAngle(settings.zeroRange, settings.sightHeight); private set

    // Torretas (en clics)
    var elevClicks = 0
    var windClicks = 0
    val elevRad get() = elevClicks * settings.turretUnit.clickRad
    val windRad get() = windClicks * settings.turretUnit.clickRad

    // Cerrojo y cargador
    val magazineSize = 5
    var rounds = magazineSize; private set
    var boltReadyAt = 0.0; private set
    var reloadUntil = 0.0; private set

    // Marcador
    var score = 0; private set
    var hits = 0; private set
    var shotsFired = 0; private set
    var kills = 0; private set

    /** Azimut central de la "zona de caza" (dirección en la que miraba el tirador al empezar). */
    var arenaAzimuthDeg = 0.0
    private var nextId = 1

    fun rezero() {
        solver.cartridge = cartridge
        zeroAngle = solver.zeroAngle(settings.zeroRange, settings.sightHeight)
    }

    fun spawnInitial(azimuthDeg: Double) {
        arenaAzimuthDeg = azimuthDeg
        targets.clear()
        when (settings.targetSet) {
            TargetSet.PRACTICE -> {
                val ranges = listOf(100.0, 200.0, 300.0, 400.0, 500.0, 600.0, 800.0, 1000.0)
                ranges.forEachIndexed { i, r ->
                    val az = azimuthDeg + (i - ranges.size / 2.0) * 3.5
                    targets.add(VirtualTarget(nextId++, TargetKind.STEEL, 0, polar(az, r), Math.toRadians(az + 180)))
                }
            }
            else -> repeat(if (settings.targetSet == TargetSet.HUMANS) 5 else 6) { spawnOne() }
        }
    }

    private fun polar(azDeg: Double, r: Double): Vec3 {
        val a = Math.toRadians(azDeg)
        return Vec3(sin(a) * r, cos(a) * r, 0.0)
    }

    private fun randomPointInArena(): Vec3 {
        val az = arenaAzimuthDeg + (rnd.nextDouble() - 0.5) * 90
        val r = settings.minRange + rnd.nextDouble() * (settings.maxRange - settings.minRange)
        return polar(az, r)
    }

    private fun spawnOne() {
        val kind = when (settings.targetSet) {
            TargetSet.HUMANS -> TargetKind.HUMAN
            else -> listOf(TargetKind.DEER, TargetKind.BOAR, TargetKind.GUANACO, TargetKind.DEER)[rnd.nextInt(4)]
        }
        val p = randomPointInArena()
        val t = VirtualTarget(nextId++, kind, rnd.nextInt(10), p, rnd.nextDouble() * Math.PI * 2)
        t.waypoint = randomPointInArena()
        t.walkPhase = rnd.nextDouble() * 6.0
        targets.add(t)
    }

    // ---------------------------------------------------------------- disparo

    val canFire get() = rounds > 0 && time >= boltReadyAt && time >= reloadUntil

    /** Dirección del ánima según la línea de mira de la cámara, el cero y las torretas. */
    fun boreDirection(cam: CameraModel): Vec3 {
        val e = zeroAngle + elevRad
        val w = windRad
        return (cam.forward + cam.up * kotlin.math.tan(e) + cam.right * kotlin.math.tan(w)).normalized()
    }

    fun muzzle(cam: CameraModel) = cam.position - cam.up * settings.sightHeight

    fun fire(cam: CameraModel): Boolean {
        if (!canFire) return false
        rounds--
        shotsFired++
        boltReadyAt = time + 1.1
        val origin = muzzle(cam)
        val dir = boreDirection(cam)
        val traj = solver.simulate(origin, dir, wind.vector(), maxRange = 2000.0, maxTime = 5.0)
        events.add(GameEvent.Fired(cartridge.name))
        if (settings.mode == GameMode.VIRTUAL) {
            shots.add(Shot(traj, origin, time))
        } else {
            planRealShot(traj, origin, cam)
        }
        return true
    }

    fun reload() {
        if (rounds == magazineSize || time < reloadUntil) return
        reloadUntil = time + 2.2
        rounds = magazineSize
    }

    // ---------------------------------------------------------------- actualización

    fun update(dt: Double) {
        time += dt
        wind.update(time)
        updateTargets(dt)
        updateShots(dt)
        updateRealShots()
        realTracks.removeAll { time - it.lastSeen > 1.2 && time > it.hitUntil }
    }

    private fun updateTargets(dt: Double) {
        val toRespawn = ArrayList<VirtualTarget>()
        for (t in targets) {
            when (t.state) {
                VirtualTarget.State.WALK, VirtualTarget.State.RUN -> {
                    val d = t.waypoint - t.pos
                    val dist = sqrt(d.x * d.x + d.y * d.y)
                    if (dist < 1.5) {
                        if (t.state == VirtualTarget.State.RUN || rnd.nextDouble() < 0.5) {
                            t.state = VirtualTarget.State.IDLE
                            t.timer = 2.0 + rnd.nextDouble() * 6
                        }
                        t.waypoint = randomPointInArena()
                    } else {
                        val desired = atan2(d.x, d.y)
                        var diff = desired - t.heading
                        while (diff > Math.PI) diff -= 2 * Math.PI
                        while (diff < -Math.PI) diff += 2 * Math.PI
                        t.heading += diff.coerceIn(-1.5 * dt, 1.5 * dt)
                        val step = t.speed * dt
                        t.pos = t.pos + Vec3(sin(t.heading) * step, cos(t.heading) * step, 0.0)
                        t.walkPhase += dt * t.speed * (if (t.kind.isHuman) 4.2 else 3.4)
                    }
                }
                VirtualTarget.State.IDLE -> {
                    if (t.kind != TargetKind.STEEL) {
                        t.timer -= dt
                        if (t.timer <= 0) t.state = VirtualTarget.State.WALK
                    }
                    t.walkPhase *= 0.9
                }
                VirtualTarget.State.DOWN -> {
                    t.fall = (t.fall + dt * 1.8).coerceAtMost(1.0)
                    if (time - t.downTime > 8.0) toRespawn.add(t)
                }
            }
            t.steelSwing *= max(0.0, 1 - dt * 1.5)
            val walkAmount = when (t.state) {
                VirtualTarget.State.WALK -> 1.0
                VirtualTarget.State.RUN -> 1.6
                else -> 0.0
            }
            val pos = if (t.kind == TargetKind.STEEL) t.pos else t.pos
            t.boxes = Models.toWorld(t.parts, pos, t.heading, t.walkPhase, walkAmount, t.fall)
        }
        if (toRespawn.isNotEmpty()) {
            targets.removeAll(toRespawn.toSet())
            repeat(toRespawn.size) { spawnOne() }
        }
    }

    private fun updateShots(dt: Double) {
        for (s in shots) {
            if (s.done) continue
            val t0 = s.t
            val t1 = s.t + dt
            s.t = t1
            val pts = s.traj.points
            var prev: TrajPoint? = s.traj.at(t0)
            if (prev == null) { s.done = true; continue }
            for (p in pts) {
                if (p.t <= t0) continue
                val stop = p.t > t1
                val cur = if (stop) (s.traj.at(t1) ?: p) else p
                if (checkSegment(s, prev!!.pos, cur.pos, cur.t)) { s.done = true; break }
                s.tracer.add(cur.pos)
                if (s.tracer.size > 40) s.tracer.removeAt(0)
                prev = cur
                if (stop) break
            }
            if (s.t > pts.last().t) s.done = true
        }
        shots.removeAll { it.done && time - it.firedAt > 6 }
    }

    private fun checkSegment(s: Shot, a: Vec3, b: Vec3, tFlight: Double): Boolean {
        // Blancos
        var best: Pair<VirtualTarget, WorldBox>? = null
        var bestF = 2.0
        for (t in targets) {
            if (!t.alive && t.kind != TargetKind.STEEL) continue
            val c = t.pos
            val mid = (a + b) * 0.5
            val segLen = (b - a).length()
            if ((mid - c).length() > segLen / 2 + 3.0) {
                continue
            }
            for (box in t.boxes) {
                val f = Models.segmentHitsBox(a, b, box) ?: continue
                if (f < bestF) { bestF = f; best = t to box }
            }
        }
        // Cerca: el chasquido supersónico asusta al animal/persona.
        for (t in targets) {
            if (!t.alive || t.kind == TargetKind.STEEL || best?.first === t) continue
            val dist = distPointSegment(t.pos + Vec3(0.0, 0.0, 1.0), a, b)
            if (dist < 4.0 && t.state != VirtualTarget.State.RUN) {
                t.state = VirtualTarget.State.RUN
                t.waypoint = fleePoint(t)
                events.add(GameEvent.NearMiss(t.kind.label))
            }
        }
        val hit = best
        if (hit != null) {
            val (t, box) = hit
            val p = a + (b - a) * bestF
            val dist = (p - s.origin).length()
            registerVirtualHit(t, box.zone, p, dist, tFlight)
            return true
        }
        // Suelo
        if (b.z <= 0.0) {
            val f = if (a.z - b.z > 1e-9) a.z / (a.z - b.z) else 1.0
            val p = a + (b - a) * f
            events.add(GameEvent.Impact(Vec3(p.x, p.y, 0.0), (p - s.origin).length()))
            return true
        }
        return false
    }

    private fun fleePoint(t: VirtualTarget): Vec3 {
        val away = Vec3(t.pos.x, t.pos.y, 0.0).normalized()
        val side = Vec3(-away.y, away.x, 0.0) * ((rnd.nextDouble() - 0.5) * 120)
        return t.pos + away * (60 + rnd.nextDouble() * 80) + side
    }

    private fun registerVirtualHit(t: VirtualTarget, zone: Zone, p: Vec3, dist: Double, tof: Double) {
        hits++
        if (t.kind == TargetKind.STEEL) {
            t.steelSwing = 1.0
            score += (dist / 10).toInt() + 10
            events.add(GameEvent.Steel(dist, p))
            return
        }
        val lethal = zone == Zone.HEAD || zone == Zone.VITAL || (t.wounded && zone == Zone.BODY)
        if (lethal) {
            t.state = VirtualTarget.State.DOWN
            t.downTime = time
            kills++
            val bonus = if (zone == Zone.HEAD) 2.0 else if (zone == Zone.VITAL) 1.5 else 1.0
            score += ((50 + dist / 4) * bonus).toInt()
        } else {
            t.wounded = true
            t.state = VirtualTarget.State.RUN
            t.waypoint = fleePoint(t)
            score += 10
        }
        events.add(GameEvent.Hit(t.kind.label, zone, dist, lethal, t.kind.isHuman, p, 0.0))
    }

    private fun distPointSegment(p: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b - a
        val l2 = ab dot ab
        val k = if (l2 < 1e-12) 0.0 else ((p - a) dot ab / l2).coerceIn(0.0, 1.0)
        return (a + ab * k - p).length()
    }

    // ---------------------------------------------------------------- modo real

    /** Registra detecciones nuevas (dirección del centro y tamaño angular). */
    fun updateRealDetections(dets: List<RealDetection>) {
        for (d in dets) {
            val match = realTracks.filter { it.cocoName == d.cocoName }
                .minByOrNull { angleBetween(it.dir, d.dir) }
                ?.takeIf { angleBetween(it.dir, d.dir) < max(0.08, it.angW) }
            if (match != null) {
                match.dir = (match.dir * 0.4 + d.dir * 0.6).normalized()
                match.angW = match.angW * 0.5 + d.angW * 0.5
                match.angH = match.angH * 0.5 + d.angH * 0.5
                match.range = if (d.range.isFinite()) match.range * 0.7 + d.range * 0.3 else match.range
                match.lastSeen = time
            } else {
                realTracks.add(RealTrack(d.label, d.cocoName, d.dir, d.angW, d.angH, d.range, time))
            }
        }
    }

    private fun angleBetween(a: Vec3, b: Vec3) = kotlin.math.acos((a dot b).coerceIn(-1.0, 1.0))

    private fun pointAtDistance(traj: Trajectory, origin: Vec3, d: Double): TrajPoint? {
        val pts = traj.points
        for (i in 1 until pts.size) {
            val da = (pts[i - 1].pos - origin).length(); val db = (pts[i].pos - origin).length()
            if (da <= d && db >= d) {
                val k = if (db > da) (d - da) / (db - da) else 0.0
                val a = pts[i - 1]; val b = pts[i]
                return TrajPoint(a.t + (b.t - a.t) * k, a.pos + (b.pos - a.pos) * k, a.vel + (b.vel - a.vel) * k)
            }
        }
        return null
    }

    private fun planRealShot(traj: Trajectory, origin: Vec3, cam: CameraModel) {
        // Blanco más cercano a la retícula; si no hay, se usa la distancia de cero.
        val track = realTracks.minByOrNull { angleBetween(it.dir, cam.forward) }
            ?.takeIf { angleBetween(it.dir, cam.forward) < max(0.05, it.angW * 1.5) }
        val dist = track?.range?.takeIf { it.isFinite() } ?: settings.zeroRange
        val p = pointAtDistance(traj, cam.position, dist) ?: return
        val dir = (p.pos - cam.position).normalized()
        pendingReal.add(PendingRealShot(traj, origin, time, time + p.t, track, dir, dist))
    }

    private fun updateRealShots() {
        val it = pendingReal.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (time < s.impactTime) continue
            it.remove()
            val tr = s.track
            var hitZone: Zone? = null
            if (tr != null && realTracks.contains(tr)) {
                // Desvío angular del impacto respecto del centro del blanco, en su propio marco.
                val fwd = tr.dir
                val right = (fwd cross Vec3.UP).normalized()
                val up = right cross fwd
                val dx = atan2(s.impactDir dot right, s.impactDir dot fwd)
                val dy = atan2(s.impactDir dot up, s.impactDir dot fwd)
                val hw = tr.angW / 2; val hh = tr.angH / 2
                if (abs(dx) < hw * 0.75 && abs(dy) < hh * 0.92) {
                    hitZone = if (tr.isHuman) {
                        when {
                            dy > hh * 0.62 && abs(dx) < hw * 0.35 -> Zone.HEAD
                            dy > hh * 0.05 && dy < hh * 0.6 && abs(dx) < hw * 0.45 -> Zone.VITAL
                            abs(dx) < hw * 0.45 && dy > -hh * 0.1 -> Zone.BODY
                            else -> Zone.LIMB
                        }
                    } else {
                        when {
                            abs(dx) < hw * 0.3 && abs(dy) < hh * 0.3 -> Zone.VITAL
                            dy > -hh * 0.3 -> Zone.BODY
                            else -> Zone.LIMB
                        }
                    }
                }
            }
            if (hitZone != null && tr != null) {
                hits++
                val down = hitZone == Zone.HEAD || hitZone == Zone.VITAL
                if (down) kills++
                score += if (down) (50 + s.distance / 4).toInt() else 10
                tr.hitUntil = time + 2.0
                tr.hitText = when (hitZone) {
                    Zone.HEAD -> "${tr.label} · impacto en cabeza"
                    Zone.VITAL -> "${tr.label} · impacto vital"
                    Zone.BODY -> "${tr.label} · impacto"
                    else -> "${tr.label} · impacto leve"
                }
                events.add(GameEvent.Hit(tr.label, hitZone, s.distance, down, tr.isHuman, null, 0.0))
            } else {
                events.add(GameEvent.RealImpact(s.impactDir, s.distance))
            }
        }
    }

    // ---------------------------------------------------------------- telémetro

    /** Distancia a lo que hay bajo la retícula (blancos virtuales o suelo). */
    fun rangeUnderReticle(cam: CameraModel): Double? {
        val a = cam.position
        val b = a + cam.forward * 3000.0
        var best: Double? = null
        if (settings.mode == GameMode.VIRTUAL) {
            for (t in targets) for (box in t.boxes) {
                val f = Models.segmentHitsBox(a, b, box, 0.15) ?: continue
                val d = f * 3000.0
                if (best == null || d < best) best = d
            }
        } else {
            realTracks.filter { angleBetween(it.dir, cam.forward) < max(0.03, it.angW / 2) }
                .minByOrNull { it.range }?.let { if (it.range.isFinite()) best = it.range }
        }
        if (best == null && cam.forward.z < -0.0005) {
            best = cam.position.z / -cam.forward.z
        }
        return best?.takeIf { it < 3000 }
    }
}

/** Detección ya convertida a coordenadas del mundo. */
data class RealDetection(val label: String, val cocoName: String, val dir: Vec3, val angW: Double, val angH: Double, val range: Double)
