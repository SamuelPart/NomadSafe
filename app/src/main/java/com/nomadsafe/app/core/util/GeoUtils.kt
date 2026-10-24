package com.nomadsafe.app.core.util

import com.nomadsafe.app.domain.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometría esférica mínima que necesita el motor de riesgo.
 *
 * Implementación propia a propósito, en vez de añadir `mapbox-sdk-turf`:
 * estas funciones son puro Kotlin sin dependencias de Android, así que corren
 * en tests de JVM en milisegundos. Turf obligaría a tests instrumentados o a
 * mockear, y la matemática es exactamente la que necesitamos y nada más.
 *
 * Todas las distancias en metros, todos los ángulos en grados salvo que se
 * indique lo contrario.
 */
object GeoUtils {

    /** Radio medio terrestre en metros (WGS84). */
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /**
     * Distancia ortodrómica (haversine). Precisión suficiente hasta ~50 km;
     * a escala de cuadra el error es despreciable frente al ruido del GPS.
     */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double = distanceMeters(
        a.latitude, a.longitude, b.latitude, b.longitude,
    )

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)

        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Rumbo inicial de `a` hacia `b`, en grados 0–360 desde el norte verdadero. */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val rLat1 = Math.toRadians(a.latitude)
        val rLat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)

        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Punto a `distanceMeters` de [origin] siguiendo [bearingDegrees].
     * Se usa para proyectar la posición estimada entre dos lecturas de GPS,
     * que en ciudad llegan con 1–2 s de separación.
     */
    fun destination(origin: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val rLat = Math.toRadians(origin.latitude)
        val rLon = Math.toRadians(origin.longitude)

        val sinLat = sin(rLat) * cos(angularDistance) +
            cos(rLat) * sin(angularDistance) * cos(bearing)
        val lat = kotlin.math.asin(sinLat)
        val lon = rLon + atan2(
            sin(bearing) * sin(angularDistance) * cos(rLat),
            cos(angularDistance) - sin(rLat) * sinLat,
        )

        return GeoPoint(
            latitude = Math.toDegrees(lat),
            longitude = ((Math.toDegrees(lon) + 540) % 360) - 180,
        )
    }

    /**
     * Distancia de un punto a un segmento, aproximada a plano local.
     *
     * A escala de cuadra (cientos de metros) la curvatura es irrelevante, así
     * que se proyecta a un sistema métrico local centrado en [segmentStart]:
     * x = Δlon · cos(lat), y = Δlat. Esto es ~100× más rápido que haversine y
     * el motor de riesgo lo llama miles de veces por ruta.
     */
    fun distanceToSegmentMeters(
        point: GeoPoint,
        segmentStart: GeoPoint,
        segmentEnd: GeoPoint,
    ): Double {
        val refLatCos = cos(Math.toRadians(point.latitude))

        val px = 0.0
        val py = 0.0
        val ax = (segmentStart.longitude - point.longitude) * refLatCos
        val ay = segmentStart.latitude - point.latitude
        val bx = (segmentEnd.longitude - point.longitude) * refLatCos
        val by = segmentEnd.latitude - point.latitude

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy

        // Segmento degenerado (inicio == fin): la distancia es al punto.
        val t = if (lengthSquared == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / lengthSquared
        val clampedT = max(0.0, min(1.0, t))

        val closestX = ax + clampedT * dx
        val closestY = ay + clampedT * dy

        // De vuelta a metros: 1 grado de latitud ≈ 111.320 km.
        val metersPerDegreeLat = 111_320.0
        val distDegrees = sqrt(closestX * closestX + closestY * closestY)
        return distDegrees * metersPerDegreeLat
    }

    /**
     * Bounding box de [center] expandido [radiusMeters]. Se usa para acotar las
     * consultas a PostGIS (`ST_MakeEnvelope`) y no traer incidentes de toda la
     * ciudad cuando el usuario sólo ve seis cuadras.
     */
    fun boundingBox(center: GeoPoint, radiusMeters: Double): BoundingBox {
        val metersPerDegreeLat = 111_320.0
        val deltaLat = radiusMeters / metersPerDegreeLat
        val metersPerDegreeLon = 111_320.0 * max(0.01, cos(Math.toRadians(center.latitude)))
        val deltaLon = radiusMeters / metersPerDegreeLon

        return BoundingBox(
            south = center.latitude - deltaLat,
            west = center.longitude - deltaLon,
            north = center.latitude + deltaLat,
            east = center.longitude + deltaLon,
        )
    }
}

/** Rectángulo geográfico en grados, listo para `ST_MakeEnvelope(w, s, e, n, 4326)`. */
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)
