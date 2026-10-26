/*
 * Copyright 2026 Henry Nguyen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.thehenrylabs.aetherenglish

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidThermalMonitor(context: Context) : ThermalMonitor {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val executor = Executors.newSingleThreadExecutor()

    override fun current(): ThermalSnapshot {
        val now = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < 29) {
            return ThermalSnapshot(ThermalBand.NONE, now, supported = false)
        }
        return ThermalSnapshot(ThermalPolicy.mapAndroidStatus(power.currentThermalStatus), now, supported = true)
    }

    override fun changes(): Flow<ThermalSnapshot> = callbackFlow {
        if (Build.VERSION.SDK_INT < 29) {
            trySend(current())
            awaitClose { }
            return@callbackFlow
        }
        val listener = PowerManager.OnThermalStatusChangedListener { trySend(current()) }
        power.addThermalStatusListener(executor, listener)
        trySend(current())
        awaitClose { power.removeThermalStatusListener(listener) }
    }

    override fun close() {
        executor.shutdown()
    }
}
