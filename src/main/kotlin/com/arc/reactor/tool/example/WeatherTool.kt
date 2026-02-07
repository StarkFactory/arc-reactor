package com.arc.reactor.tool.example

import com.arc.reactor.tool.DefaultToolCategory
import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * 날씨 도구 (예시) — LocalTool + @Tool 어노테이션 방식
 *
 * CalculatorTool이 ToolCallback 인터페이스를 직접 구현하는 예시라면,
 * 이 클래스는 LocalTool + @Tool 어노테이션 방식을 보여줍니다.
 *
 * ## ToolCallback vs LocalTool 차이
 * - **ToolCallback**: name, description, inputSchema, call()을 직접 구현. JSON 스키마를 수동 작성.
 * - **LocalTool + @Tool**: 어노테이션으로 선언. 스키마 자동 생성. Spring DI 주입 가능.
 *
 * ## Spring DI 활용 패턴
 * 실제 프로젝트에서는 Service나 Repository를 생성자 주입받아 사용합니다:
 * ```kotlin
 * @Component
 * class OrderTool(
 *     private val orderService: OrderService  // Spring DI 주입
 * ) : LocalTool {
 *     override val category = DefaultToolCategory.SEARCH
 *
 *     @Tool(description = "주문 상태를 조회합니다")
 *     fun getOrderStatus(@ToolParam(description = "주문 번호") orderId: String): String {
 *         return orderService.findById(orderId)?.status ?: "주문을 찾을 수 없습니다"
 *     }
 * }
 * ```
 *
 * ## 활성화 방법
 * 이 클래스에 @Component를 추가하면 자동 등록됩니다.
 */
// @Component  ← 주석 해제하면 자동 등록
class WeatherTool : LocalTool {

    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "Get current weather information for a city")
    fun getWeather(
        @ToolParam(description = "City name (e.g., Seoul, Tokyo, New York)") city: String
    ): String {
        // 실제 프로젝트에서는 외부 API를 호출합니다:
        // return weatherApiClient.getCurrentWeather(city)
        return "Weather in $city: Sunny, 22°C, Humidity 45%"
    }

    @Tool(description = "Get weather forecast for the next days")
    fun getForecast(
        @ToolParam(description = "City name") city: String,
        @ToolParam(description = "Number of days (1-7)") days: Int
    ): String {
        val validDays = days.coerceIn(1, 7)
        return "Forecast for $city (next $validDays days): Mostly sunny, 20-25°C"
    }
}
