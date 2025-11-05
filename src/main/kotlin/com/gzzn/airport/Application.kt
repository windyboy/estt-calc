package com.gzzn.airport

import io.micronaut.runtime.Micronaut.run
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Contact
import io.swagger.v3.oas.annotations.info.Info

@OpenAPIDefinition(
	info = Info(
		title = "ESTT Flight Time Calculator",
		version = "0.1.1",
		description = "Service for calculating estimated flying times for arriving flights based on seasonal schedules and historical data",
		contact = Contact(
			name = "Airport Systems",
			email = "fengzhq@it2000.com.cn"
		)
	)
)
object Api

fun main(args: Array<String>) {
	run(*args)
}
