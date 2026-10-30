package com.guileman.snipervr.ui

import android.content.Context
import com.guileman.snipervr.core.TurretUnit
import com.guileman.snipervr.game.GameMode
import com.guileman.snipervr.game.GameSettings
import com.guileman.snipervr.game.TargetSet

/** Guarda la configuración del menú entre sesiones. */
object Prefs {
    private const val NAME = "snipervr"

    fun load(ctx: Context): GameSettings {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val d = GameSettings()
        return GameSettings(
            mode = GameMode.entries.getOrElse(p.getInt("mode", d.mode.ordinal)) { d.mode },
            targetSet = TargetSet.entries.getOrElse(p.getInt("targets", d.targetSet.ordinal)) { d.targetSet },
            cartridgeIndex = p.getInt("cart", d.cartridgeIndex),
            zeroRange = p.getFloat("zero", d.zeroRange.toFloat()).toDouble(),
            turretUnit = TurretUnit.entries.getOrElse(p.getInt("unit", d.turretUnit.ordinal)) { d.turretUnit },
            magnification = p.getFloat("mag", d.magnification.toFloat()).toDouble(),
            eyeDistanceM = p.getFloat("eye", d.eyeDistanceM.toFloat()).toDouble(),
            scopeCircle = p.getBoolean("circle", d.scopeCircle),
            assist = p.getBoolean("assist", d.assist),
            maxWind = p.getFloat("wind", d.maxWind.toFloat()).toDouble(),
            useCompass = p.getBoolean("compass", d.useCompass),
            syntheticScene = p.getBoolean("synthetic", d.syntheticScene),
            minRange = p.getFloat("minR", d.minRange.toFloat()).toDouble(),
            maxRange = p.getFloat("maxR", d.maxRange.toFloat()).toDouble(),
            temperatureC = p.getFloat("temp", d.temperatureC.toFloat()).toDouble(),
            altitudeM = p.getFloat("alt", d.altitudeM.toFloat()).toDouble()
        )
    }

    fun save(ctx: Context, s: GameSettings) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("mode", s.mode.ordinal)
            .putInt("targets", s.targetSet.ordinal)
            .putInt("cart", s.cartridgeIndex)
            .putFloat("zero", s.zeroRange.toFloat())
            .putInt("unit", s.turretUnit.ordinal)
            .putFloat("mag", s.magnification.toFloat())
            .putFloat("eye", s.eyeDistanceM.toFloat())
            .putBoolean("circle", s.scopeCircle)
            .putBoolean("assist", s.assist)
            .putFloat("wind", s.maxWind.toFloat())
            .putBoolean("compass", s.useCompass)
            .putBoolean("synthetic", s.syntheticScene)
            .putFloat("minR", s.minRange.toFloat())
            .putFloat("maxR", s.maxRange.toFloat())
            .putFloat("temp", s.temperatureC.toFloat())
            .putFloat("alt", s.altitudeM.toFloat())
            .apply()
    }
}
