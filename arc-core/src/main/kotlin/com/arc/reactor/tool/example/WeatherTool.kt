package com.arc.reactor.tool.example

import com.arc.reactor.tool.DefaultToolCategory
import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * Weather tool (example) — LocalTool + @Tool annotation approach
 *
 * While CalculatorTool demonstrates direct ToolCallback interface implementation,
 * this class showcases the LocalTool + @Tool annotation approach.
 *
 * ## ToolCallback vs LocalTool differences
 * - **ToolCallback**: Directly implement name, description, inputSchema, call(). Manually write JSON schema.
 * - **LocalTool + @Tool**: Declared via annotations. Schema auto-generated. Supports Spring DI injection.
 *
 * ## Spring DI usage pattern
 * In real projects, inject Services or Repositories via constructor injection:
 * ```kotlin
 * @Component
 * class OrderTool(
 *     private val orderService: OrderService  // Spring DI injection
 * ) : LocalTool {
 *     override val category = DefaultToolCategory.SEARCH
 *
 *     @Tool(description = "Retrieves order status")
 *     fun getOrderStatus(@ToolParam(description = "Order ID") orderId: String): String {
 *         return orderService.findById(orderId)?.status ?: "Order not found"
 *     }
 * }
 * ```
 *
 * ## How to activate
 * Add @Component to this class to auto-register it.
 */
// @Component  ← Uncomment to auto-register
class WeatherTool : LocalTool {

    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "Get current weather information for a city")
    fun getWeather(
        @ToolParam(description = "City name (e.g., Seoul, Tokyo, New York)") city: String
    ): String {
        // In a real project, call an external API:
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
