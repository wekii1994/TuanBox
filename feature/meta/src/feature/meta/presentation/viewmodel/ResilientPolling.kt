/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/** Keeps the last successful value on transport failure. Cancellation belongs to the caller. */
internal fun <T> resilientPolling(
    initial: T,
    query: suspend () -> T,
    stale: (T) -> T,
): Flow<T> = flow {
    var lastGood = initial
    var failures = 0
    emit(initial)
    while (currentCoroutineContext().isActive) {
        val next = try {
            query().also {
                lastGood = it
                failures = 0
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failures = (failures + 1).coerceAtMost(4)
            stale(lastGood)
        }
        emit(next)
        delay(if (failures == 0) 2000L else (2000L shl failures).coerceAtMost(30000L))
    }
}
