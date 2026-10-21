package com.termux.zerocore.ai.agent

/**
 * 端点档案：描述一个 API 端点"什么稀缺、多久重置"。
 *
 * 省什么完全取决于这个：卡请求次数的端点上，为了省 token 而多发一次请求是净亏；
 * 卡 token 的端点反过来。所以所有节流决策都从这里读，不写死在调用处。
 *
 * 这些数值变动很快，这里只放默认值，用户可在设置里覆盖。
 */
data class ZtEndpointProfile(
    val id: String,
    /** 每分钟请求上限，用于令牌桶。 */
    val rpm: Int,
    /** 每天请求上限，0 表示不限。 */
    val dailyRequests: Int = 0,
    /** 每天 token 上限，0 表示不限。 */
    val dailyTokens: Long = 0,
    /** token 是否不计费。为 true 时不需要为省 token 付出额外请求。 */
    val tokenUnlimited: Boolean = false,
    /** 最大并发。免费端点普遍很低，超了会排队或被打回。 */
    val maxConcurrency: Int = 1,
    /** 是否按积分计费。为 true 时不做无谓重试。 */
    val creditBased: Boolean = false,
    /** 429 是否值得重试。按请求数硬限流的端点重试往往无效但成本低，仍重试。 */
    val retryOn429: Boolean = true
) {
    /** 令牌桶容量：留一点突发余量，但不超过 rpm。 */
    val burst: Int get() = (rpm / 2).coerceAtLeast(1).coerceAtMost(rpm)

    companion object {
        val DEFAULT = ZtEndpointProfile(
            id = "default",
            rpm = 60,
            maxConcurrency = 2
        )
    }
}

object ZtEndpointProfiles {

    /**
     * 按 host 猜一份档案。识别不出来就回落到宽松的通用档案，
     * 宁可不限速让用户自己踩到 429，也不要把正常端点限死。
     */
    fun fromUrl(rawUrl: String): ZtEndpointProfile {
        val host = runCatching {
            val trimmed = rawUrl.trim().lowercase()
            val noScheme = trimmed.substringAfter("://")
            noScheme.substringBefore("/").substringBefore(":")
        }.getOrDefault("")

        val isLocal = host == "127.0.0.1" || host == "localhost" ||
            host == "0.0.0.0" || host == "[::1]"

        return when {
            isLocal -> ZtEndpointProfile(
                id = "local-gateway",
                rpm = 600,
                maxConcurrency = 4,
                retryOn429 = false
            )

            host.contains("agnes-ai") -> ZtEndpointProfile(
                id = "agnes-free",
                rpm = 10,
                tokenUnlimited = true,
                maxConcurrency = 1,
                retryOn429 = true
            )

            host.contains("bigmodel.cn") -> ZtEndpointProfile(
                id = "zhipu",
                rpm = 60,
                maxConcurrency = 1,
                retryOn429 = true
            )

            host.contains("openrouter.ai") -> ZtEndpointProfile(
                id = "openrouter-free",
                rpm = 20,
                dailyRequests = 50,
                creditBased = true,
                maxConcurrency = 1
            )

            host.contains("aihubmix") -> ZtEndpointProfile(
                id = "aihubmix-free",
                rpm = 10,
                dailyRequests = 100,
                dailyTokens = 1_000_000,
                maxConcurrency = 1
            )

            host.contains("routeway") || host.contains("siliconflow") -> ZtEndpointProfile(
                id = "free-aggregator",
                rpm = 20,
                dailyRequests = 200,
                maxConcurrency = 1
            )

            else -> ZtEndpointProfile.DEFAULT
        }
    }
}
