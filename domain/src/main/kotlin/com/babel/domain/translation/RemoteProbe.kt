package com.babel.domain.translation

import com.babel.domain.settings.RemoteProviderSettings

/**
 * Asks a remote service whether the settings a user just typed actually work.
 *
 * Without this, a wrong key is invisible twice over: nothing on save says so,
 * and nothing at use time does either — translations simply do not appear, and
 * every explanation for that looks the same from the outside.
 *
 * It makes one real request, because there is nothing else that would be
 * evidence. An address can be parsed and still 404, a key can be well-formed
 * and still be revoked, and a model name can be spelled perfectly and not exist
 * on that account. The status the service answers with is the only thing that
 * separates those, which is why [ProbeResult] is shaped like the statuses
 * rather than like a boolean.
 *
 * Advisory, never a gate: somebody configuring this on a train should still be
 * able to save.
 */
interface RemoteProbe {

    /**
     * @param settings the **draft** the user is looking at, which is usually
     *   not what is stored — the point is to check before committing.
     */
    suspend fun check(settings: RemoteProviderSettings): ProbeResult
}

/**
 * What a service said when asked.
 *
 * Each case is something a user can act on, which is the whole reason for not
 * collapsing this into a boolean. The mapping from status to case lives with
 * the implementation, because it is protocol knowledge.
 */
sealed interface ProbeResult {

    /** It answered with a translation. */
    data object Ok : ProbeResult

    /** 401/403 — the credential is missing, wrong, or revoked. */
    data object KeyRejected : ProbeResult

    /** 404 — the address is reachable and there is nothing at that path. */
    data object EndpointNotFound : ProbeResult

    /** 400/422 — usually the model name, which is the only free text left. */
    data object RequestRejected : ProbeResult

    /** 429/456 — the credential works and has nothing left on it. */
    data object QuotaExhausted : ProbeResult

    /** Nothing answered: no network, wrong host, or a server that is not up. */
    data class Unreachable(val cause: String? = null) : ProbeResult

    /** It answered, and with something this does not have a name for. */
    data class Unexpected(val status: Int) : ProbeResult

    /** Nothing was asked because there was not enough to ask with. */
    data object NotConfigured : ProbeResult
}
