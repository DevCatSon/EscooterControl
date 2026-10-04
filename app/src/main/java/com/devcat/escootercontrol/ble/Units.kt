package com.devcat.escootercontrol.ble

/**
 * Unit conversion for display. The scooter always reports kilometres; the stock app converts
 * speed, trip, odometer and the max-speed readout to miles itself when the scooter's speed-unit
 * flag (status byte0 bit7) is set, using this exact factor (see its kmTMile()).
 */
object Units {
    const val KM_TO_MILES = 0.6213712

    fun speed(kmh: Double, miles: Boolean): Double = if (miles) kmh * KM_TO_MILES else kmh
    fun distance(km: Double, miles: Boolean): Double = if (miles) km * KM_TO_MILES else km

    fun speedLabel(miles: Boolean) = if (miles) "mph" else "km/h"
    fun distanceLabel(miles: Boolean) = if (miles) "mi" else "km"
}
