package com.arc.reactor.slack.handler

/**
 * 사용자 입력 인터벌을 Spring 6-field cron 표현식으로 변환한다.
 *
 * 지원 형식:
 * - 분 단위: "30m" → 30분마다
 * - 시간 단위: "1h", "2h" → N시간마다
 * - 시각 지정: "9am", "2pm", "14:30" → 매일 해당 시각
 * - 키워드: "daily", "weekly", "weekday"
 *
 * @see SlackSlashIntentParser
 */
internal object LoopIntervalParser {

    private val minuteRegex = Regex("^(\\d+)m$", RegexOption.IGNORE_CASE)
    private val hourRegex = Regex("^(\\d+)h$", RegexOption.IGNORE_CASE)
    private val amPmRegex = Regex("^(\\d{1,2})(am|pm)$", RegexOption.IGNORE_CASE)
    private val time24Regex = Regex("^(\\d{1,2}):(\\d{2})$")
    private val koreanTimeRegex = Regex("^(\\d{1,2})시(?:(\\d{1,2})분)?$")

    /** 인터벌 문자열을 cron 표현식으로 변환한다. null이면 파싱 실패. */
    fun toCron(interval: String): String? {
        val normalized = interval.trim().lowercase()
        return when {
            normalized == "daily" || normalized == "매일" -> "0 0 9 * * *"
            normalized == "weekly" || normalized == "매주" -> "0 0 9 * * MON"
            normalized == "weekday" || normalized == "평일" -> "0 0 9 * * MON-FRI"
            minuteRegex.matches(normalized) -> parseMinutes(normalized)
            hourRegex.matches(normalized) -> parseHours(normalized)
            amPmRegex.matches(normalized) -> parseAmPm(normalized)
            time24Regex.matches(normalized) -> parseTime24(normalized)
            koreanTimeRegex.matches(normalized) -> parseKoreanTime(normalized)
            else -> null
        }
    }

    /** cron 표현식을 사람이 읽을 수 있는 한국어 설명으로 변환한다. */
    fun toDescription(interval: String): String {
        val normalized = interval.trim().lowercase()
        return when {
            normalized == "daily" || normalized == "매일" -> "매일 09:00 KST"
            normalized == "weekly" || normalized == "매주" -> "매주 월요일 09:00 KST"
            normalized == "weekday" || normalized == "평일" -> "평일 09:00 KST"
            minuteRegex.matches(normalized) -> {
                val m = minuteRegex.matchEntire(normalized)!!.groupValues[1]
                "${m}분마다"
            }
            hourRegex.matches(normalized) -> {
                val h = hourRegex.matchEntire(normalized)!!.groupValues[1]
                "${h}시간마다"
            }
            amPmRegex.matches(normalized) -> {
                val match = amPmRegex.matchEntire(normalized)!!
                val hour = match.groupValues[1].toInt()
                val isPm = match.groupValues[2].lowercase() == "pm"
                val h24 = if (isPm && hour != 12) hour + 12 else if (!isPm && hour == 12) 0 else hour
                "매일 %02d:00 KST".format(h24)
            }
            time24Regex.matches(normalized) -> "매일 $normalized KST"
            koreanTimeRegex.matches(normalized) -> {
                val match = koreanTimeRegex.matchEntire(normalized)!!
                val h = match.groupValues[1].toInt()
                val m = match.groupValues[2].ifBlank { "0" }.toInt()
                "매일 %02d:%02d KST".format(h, m)
            }
            else -> interval
        }
    }

    /** 최소 간격(30분) 검증. true면 허용. */
    fun isValidMinInterval(interval: String): Boolean {
        val normalized = interval.trim().lowercase()
        val minuteMatch = minuteRegex.matchEntire(normalized)
        if (minuteMatch != null) {
            val minutes = minuteMatch.groupValues[1].toIntOrNull() ?: return false
            return minutes >= MIN_INTERVAL_MINUTES
        }
        // 시간 단위, 일별, 주별은 모두 30분 이상
        return true
    }

    private fun parseMinutes(input: String): String? {
        val minutes = minuteRegex.matchEntire(input)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (minutes < MIN_INTERVAL_MINUTES || minutes > MAX_INTERVAL_MINUTES) return null
        return "0 */$minutes * * * *"
    }

    private fun parseHours(input: String): String? {
        val hours = hourRegex.matchEntire(input)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (hours < 1 || hours > 24) return null
        return "0 0 */$hours * * *"
    }

    private fun parseAmPm(input: String): String? {
        val match = amPmRegex.matchEntire(input) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val isPm = match.groupValues[2].lowercase() == "pm"
        val h24 = when {
            isPm && hour != 12 -> hour + 12
            !isPm && hour == 12 -> 0
            else -> hour
        }
        if (h24 !in 0..23) return null
        return "0 0 $h24 * * *"
    }

    private fun parseTime24(input: String): String? {
        val match = time24Regex.matchEntire(input) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "0 $minute $hour * * *"
    }

    private fun parseKoreanTime(input: String): String? {
        val match = koreanTimeRegex.matchEntire(input) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "0 $minute $hour * * *"
    }

    private const val MIN_INTERVAL_MINUTES = 30
    private const val MAX_INTERVAL_MINUTES = 1440 // 24시간
}
