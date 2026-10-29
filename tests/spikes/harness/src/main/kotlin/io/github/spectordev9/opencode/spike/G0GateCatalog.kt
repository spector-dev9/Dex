package io.github.spectordev9.opencode.spike

data class G0GateCheck(
    val id: String,
    val title: String,
    val requirement: String,
    val acceptedDetail: String? = null,
)

object G0GateCatalog {
    val checks = listOf(
        G0GateCheck(
            "S1-A",
            "S1 Profile A",
            "Loader, authenticated health, and mock-provider prompt on the reference device.",
            acceptedDetail = "Owner-accepted 2026-10-27 harness evidence: loader, authenticated health, " +
                "and mock-provider prompt passed; production Profile A wiring and Profile B remain " +
                "post-G0 follow-ups.",
        ),
        G0GateCheck(
            "S1-B",
            "S1 Profile B",
            "Profile B must run the same reference-device flow or be resolved by the S1 decision rule.",
        ),
        G0GateCheck(
            "S2-cold",
            "S2 cold start",
            "Compare Profile A and B cold-start measurements.",
        ),
        G0GateCheck(
            "S2-rss",
            "S2 idle RSS",
            "Compare Profile A and B idle memory measurements.",
        ),
        G0GateCheck(
            "S2-git",
            "S2 git status",
            "Compare git status on a 10k-file repository.",
        ),
        G0GateCheck(
            "S2-npm",
            "S2 npm test",
            "Compare npm test through the agent for both profiles.",
        ),
        G0GateCheck(
            "S3",
            "S3 agent shell",
            "Compare actual agent bash with the guest shell and PTY environment.",
            acceptedDetail = "Owner-accepted 2026-10-27 harness evidence: PRoot guest shell and native " +
                "PTY environment matched; production agent-shell comparison remains a post-G0 follow-up.",
        ),
        G0GateCheck(
            "S4",
            "S4 SSE semantics",
            "Document resync behavior and pass STR-5 fault-injection checks.",
        ),
        G0GateCheck(
            "S5",
            "S5 endpoint inventory",
            "Reconcile pinned API docs, PTY routes, port, auth, and socket behavior.",
        ),
        G0GateCheck(
            "S6",
            "S6 OpenCode 2.x",
            "Check a distributable 2.x artifact when available; otherwise retain the documented 1.x pin.",
        ),
        G0GateCheck(
            "S9",
            "S9 release compliance",
            "Complete licensing, source-offer, signing, and distribution compliance evidence.",
        ),
        G0GateCheck(
            "BASELINES",
            "Baselines and thresholds",
            "Record reference-device baselines and ratify numeric thresholds.",
        ),
        G0GateCheck(
            "D1",
            "D1 distribution decision",
            "Freeze the distribution decision from the required compliance evidence.",
        ),
        G0GateCheck(
            "D4",
            "D4 runtime profile",
            "Freeze the runtime profile using admissible S1/S2/S3 evidence.",
        ),
        G0GateCheck(
            "D5",
            "D5 version pin",
            "Confirm the exact OpenCode baseline and documented 2.x disposition.",
        ),
    )

    val acceptedDefaults: Map<String, G0GateResult> = checks
        .mapNotNull { check -> check.acceptedDetail?.let { check.id to G0GateResult("accepted", it) } }
        .toMap()
}
