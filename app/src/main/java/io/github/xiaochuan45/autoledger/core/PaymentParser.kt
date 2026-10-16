package io.github.xiaochuan45.autoledger.core

import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Conservative local rules over transient text. No raw evidence is returned or persisted. */
object PaymentParser {
    private val success = Regex("支付成功|付款成功|扣款成功|交易成功|成功支付|成功付款|成功扣款|已成功支付|已成功付款|已成功扣款")
    private val negatedSuccess = Regex("(?:未|尚未|还未|仍未|未曾|没有|并未|无法|不能|未能|不代表|并非|不是|并不是|非)\\s*(?:支付成功|付款成功|扣款成功|交易成功|成功支付|成功付款|成功扣款)")
    private val failed = Regex("支付失败|付款失败|扣款失败|交易失败|支付未成功|付款未成功|扣款未成功|(?:支付|付款|交易|订单)(?:已)?取消|(?:订单|交易)(?:已)?关闭|待支付|待付款|等待支付|等待付款|未支付|未付款|尚未支付|尚未付款|支付处理中|付款处理中|扣款处理中|支付中|付款中|扣款中")
    private val sensitive = Regex("验证码|校验码|动态口令|登录密码|支付密码|一次性密码", RegexOption.IGNORE_CASE)
    private val income = Regex(
        "收款成功|收款到账|收款金额|已收款|收入金额|收到[^\\n]{0,40}(?:转账|付款)|" +
            "(?:交易类型|收支类型|收支|资金方向)\\s*[:：]?\\s*(?:收入|收款|转入)|(?:^|\\n)[ \\t]*收入[ \\t]*(?:$|\\n)",
    )
    private val transactionIdLabels = "微信支付交易单号|支付宝交易号|支付宝交易单号|支付交易单号|交易单号|交易号"
    private val reservedFieldName = Regex("^(?:实付金额|实际支付|实际支付金额|支付金额|付款金额|订单金额|订单总额|优惠金额|支付时间|付款时间|扣款时间|交易时间|商户名称|商户|收款方|消费平台|平台|$transactionIdLabels|商户订单号|订单号|支付成功|付款成功)$")
    private val otherField = Regex("(?:实付金额|实际支付|支付金额|付款金额|订单金额|订单总额|优惠金额|支付时间|付款时间|扣款时间|交易时间|商户名称|商户|收款方|消费平台|平台|$transactionIdLabels|商户订单号|订单号)\\s*[:：]")
    private val number = "[-+]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"
    private val amountSuffix = "\\s*(?:人民币\\s*)?[:：]?\\s*[¥￥]?\\s*($number)(?![0-9.,])\\s*(?:元)?"
    private val actualAmount = Regex("(?:实际支付金额|实际付款金额|实际扣款金额|实际支付|实际付款|实际扣款|实付金额|实付款|实付|实扣金额|扣款金额)$amountSuffix")
    private val paymentAmount = Regex("(?:支付金额|付款金额|消费金额)$amountSuffix")
    private val inlineSuccessLabel = "(?:成功支付|成功付款|成功扣款|支付成功|付款成功|扣款成功|已支付|已付款|已扣款)\\s*(?:人民币\\s*)?[:：]?\\s*"
    private val inlineCurrencyAmount = Regex("$inlineSuccessLabel[¥￥]\\s*($number)(?![0-9.,])")
    private val inlineYuanAmount = Regex("$inlineSuccessLabel($number)\\s*元")
    // Fallback is only a complete amount line, never a currency embedded in an arbitrary field.
    // Keep signs before the currency so +¥23.50/-¥23.50 cannot silently lose direction.
    private val standaloneCurrencyAmount = Regex("(?m)^[ \\t]*([-+]?)[ \\t]*[¥￥][ \\t]*($number)[ \\t]*(?:元)?[ \\t]*$")
    private val nonActualAmountContext = Regex("订单金额|订单总额|订单总价|原价|优惠|抵扣|减免|应付|余额|账户资产|资产总额|可用金额|积分|累计支出|历史消费")
    private val timePattern = Regex("(?:支付时间|付款时间|扣款时间)\\s*[:：]?\\s*([0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}日?\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)(?![0-9:])")
    private val timeFormatter = DateTimeFormatter.ofPattern("uuuu-M-d H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)

    fun parse(input: CaptureInput): ParsedPayment? {
        if (!CapturePolicy.accepts(input)) return null
        if (input.texts.size > 160 || input.texts.sumOf { it.length } > 16_384) return null
        val channel = PaymentChannel.fromPackage(input.packageName) ?: return null
        val pieces = input.texts.map(::normalize).filter(String::isNotBlank).distinct()
        val body = pieces.filterNot { it == input.title?.trim() }.joinToString("\n")
        val text = (listOfNotNull(input.title?.let(::normalize)) + body).joinToString("\n")
        if (sensitive.containsMatchIn(text)) return null
        val kind = when {
            Regex("退款|退回款项").containsMatchIn(body) -> EntryKind.REFUND
            body.contains("提现") -> EntryKind.WITHDRAWAL
            body.contains("充值") -> EntryKind.TOP_UP
            body.contains("转账") -> EntryKind.TRANSFER
            else -> EntryKind.EXPENSE
        }
        if (kind != EntryKind.REFUND && income.containsMatchIn(body)) return null
        val explicitSuccessTitle = input.source == CaptureSource.NOTIFICATION &&
            input.title?.trim() in setOf("支付成功", "付款成功", "扣款成功", "自动扣款成功")
        // A precise trusted success title is evidence; general titles (e.g. 微信支付) are not.
        val hasSuccess = explicitSuccessTitle || success.findAll(body).any { match ->
            val after = body.substring(match.range.last + 1).substringBefore('\n').trimStart()
            val before = body.substring(0, match.range.first).substringAfterLast('\n').takeLast(8).trimEnd()
            !Regex("^(?:后|时|即可|才能|可|前|与否|率|了吗|吗|？|\\?)").containsMatchIn(after) &&
                !Regex("(?:如果|假如|若|是否|等待|请确认)$").containsMatchIn(before)
        }
        val hasFailure = failed.containsMatchIn(body)
        if (kind == EntryKind.EXPENSE && negatedSuccess.containsMatchIn(text)) return null
        if (kind == EntryKind.EXPENSE && hasFailure && !hasSuccess) return null
        val related = hasSuccess || hasFailure || kind != EntryKind.EXPENSE ||
            (input.title?.let { success.containsMatchIn(it) } == true) ||
            Regex("实付|支付金额|付款金额|消费金额|扣款金额|交易状态|支付状态|付款状态|扣款状态").containsMatchIn(body)
        if (!related) return null

        val reasons = mutableListOf<String>()
        if (kind == EntryKind.EXPENSE && !hasSuccess) reasons += "未读到明确的付款成功状态"
        if (hasFailure) reasons += "同时包含未完成或失败状态，无法确认交易结果"
        if (kind != EntryKind.EXPENSE) reasons += when (kind) {
            EntryKind.REFUND -> "退款信息需核对是否到账并关联原消费，本阶段不自动计为消费或退款"
            EntryKind.TRANSFER -> "转账需手动确认资金用途，本阶段不自动计为消费"
            EntryKind.TOP_UP -> "充值属于资金流转，本阶段不自动计为消费"
            EntryKind.WITHDRAWAL -> "提现属于资金流转，本阶段不自动计为消费"
            else -> error("unreachable")
        }

        val amountResult = extractAmount(body, kind, hasSuccess)
        reasons += amountResult.reasons
        val merchant = field(body, "商户名称|商户全称|收款商户|商家名称|商户|收款方|交易对方")
        val platform = field(body, "消费平台|交易平台|平台")
        val transactionIds = fieldValues(body, transactionIdLabels).filter { Regex("[A-Za-z0-9_-]{6,80}").matches(it) }.distinct()
        // On a refund screen the ordinary trade number may identify the ORIGINAL payment.
        // It must not merge two separate partial refunds as one refund event.
        val transactionId = if (kind == EntryKind.REFUND) null else transactionIds.singleOrNull()
        if (transactionIds.size > 1) reasons += "读到多个交易单号，无法确定当前交易"
        val timeMatches = timePattern.findAll(body).map { it.groupValues[1] }.distinct().toList()
        val paymentTime = timeMatches.singleOrNull()?.let(::parseTime)
        if (timeMatches.size > 1) reasons += "读到多个付款时间，无法确定实际付款时间"
        if (timeMatches.size == 1 && paymentTime == null) reasons += "付款时间格式、日期或本地时区不明确，请核对"
        if (input.source == CaptureSource.ACCESSIBILITY && transactionId == null && paymentTime == null) {
            reasons += "缺少交易单号和付款时间，无法区分历史页面与新付款"
        }
        val status = if (reasons.isEmpty()) RecordStatus.CONFIRMED else RecordStatus.REVIEW
        val fingerprint = hash(
            channel.name, kind.name, amountResult.amount?.toString(), merchant, platform,
            paymentTime?.toString(), transactionId, status.name,
        )
        return ParsedPayment(
            amountMinor = amountResult.amount,
            channel = channel,
            merchant = merchant,
            platform = platform,
            occurredAt = paymentTime ?: input.observedAt,
            timeSource = if (paymentTime == null) TimeSource.OBSERVED else TimeSource.PAYMENT,
            kind = kind,
            status = status,
            transactionId = transactionId,
            captureKey = hash(input.source.name, channel.name, input.sourceKey, fingerprint),
            contentFingerprint = fingerprint,
            source = input.source,
            reviewReason = reasons.distinct().takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    }

    private data class AmountResult(val amount: Long?, val reasons: List<String>)

    private fun extractAmount(text: String, kind: EntryKind, hasSuccess: Boolean): AmountResult {
        val specific = when (kind) {
            EntryKind.REFUND -> Regex("(?:实际退款|退款金额|退回金额|退款到账金额)$amountSuffix")
            EntryKind.TRANSFER -> Regex("(?:转账金额|转出金额)$amountSuffix")
            EntryKind.TOP_UP -> Regex("(?:充值金额|实际充值)$amountSuffix")
            EntryKind.WITHDRAWAL -> Regex("(?:提现金额|实际提现)$amountSuffix")
            EntryKind.EXPENSE -> actualAmount
        }
        val primary = specific.findAll(text).map { it.groupValues[1] }.toList()
        val secondary = if (kind == EntryKind.EXPENSE) paymentAmount.findAll(text).map { it.groupValues[1] }.toList() else emptyList()
        val inline = if (kind == EntryKind.EXPENSE && hasSuccess) {
            (inlineCurrencyAmount.findAll(text) + inlineYuanAmount.findAll(text)).map { it.groupValues[1] }.toList()
        } else emptyList()
        val isolatedCurrency = if (kind == EntryKind.EXPENSE && hasSuccess && !nonActualAmountContext.containsMatchIn(text)) {
            standaloneCurrencyAmount.findAll(text).map { it.groupValues[1] + it.groupValues[2] }.toList()
        } else emptyList()
        val candidates = when {
            primary.isNotEmpty() -> primary + secondary + inline
            secondary.isNotEmpty() -> secondary
            inline.isNotEmpty() -> inline
            isolatedCurrency.isNotEmpty() -> isolatedCurrency
            else -> return AmountResult(null, listOf(when (kind) {
                EntryKind.EXPENSE -> "未读到明确的实际付款金额"
                EntryKind.REFUND -> "未读到明确的退款金额"
                EntryKind.TRANSFER -> "未读到明确的转账金额"
                EntryKind.TOP_UP -> "未读到明确的充值金额"
                EntryKind.WITHDRAWAL -> "未读到明确的提现金额"
            }))
        }
        if (Regex("美元|港币|港元|欧元|日元|澳元|英镑|\\b(?:USD|HKD|EUR|JPY|GBP|AUD)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return AmountResult(null, listOf("无法确认金额为人民币，本阶段仅支持人民币账目"))
        }
        val amounts = candidates.map { Amounts.parseMinor(it.replace(",", "")) }
        if (amounts.any { it == null }) return AmountResult(null, listOf("金额格式不明确、包含符号、超出范围或小数超过两位"))
        val distinct = amounts.filterNotNull().distinct()
        if (distinct.size != 1) return AmountResult(null, listOf("读到多个不同实付金额，无法确定本次付款金额"))
        val amount = distinct.single()
        return AmountResult(amount, if (amount == 0L) listOf("实付金额为零，需核对是否发生实际支出") else emptyList())
    }

    private fun field(text: String, labels: String): String? = fieldValues(text, labels).distinct().singleOrNull()

    private fun fieldValues(text: String, labels: String): List<String> {
        val pattern = Regex("(?:^|[\\n，；])[ \\t]*(?:$labels)(?:[ \\t]*[:：][ \\t]*|[ \\t]*\\n[ \\t]*|[ \\t]+)([^\\n，；]+)")
        return pattern.findAll(text).mapNotNull { match ->
            val raw = match.groupValues[1].trim()
            val nextLabel = otherField.find(raw)
            val value = (nextLabel?.let { raw.substring(0, it.range.first) } ?: raw).trim().trimEnd(',', ';')
            value.takeIf {
                it.isNotBlank() && it.length <= 80 && it !in setOf("未知", "--", "暂无") &&
                    !otherField.containsMatchIn(it) && !reservedFieldName.matches(it)
            }
        }.toList()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace('\u00a0', ' ').replace("\r\n", "\n").trim()

    private fun parseTime(value: String): Long? = runCatching {
        val normalized = value.replace(Regex("[/.年月]"), "-").replace("日", "").replace(Regex("\\s+"), " ")
        val local = LocalDateTime.parse(normalized, timeFormatter)
        val offsets = ZoneId.systemDefault().rules.getValidOffsets(local)
        // Do not shift a nonexistent wall time or choose one occurrence of an ambiguous hour.
        val offset = offsets.singleOrNull() ?: return null
        local.toInstant(offset).toEpochMilli()
    }.getOrNull()

    private fun hash(vararg fields: String?): String {
        // Length prefixes make delimiters within merchant names unambiguous.
        val structured = fields.joinToString("") { value -> if (value == null) "-1:" else "${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256").digest(structured.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
