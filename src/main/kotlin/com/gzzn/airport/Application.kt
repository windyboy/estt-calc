package com.gzzn.airport

import io.micronaut.runtime.Micronaut.*

fun main(args: Array<String>) {
	build()
	    .args(*args)
		.packages("com.gzzn.airport")
		.start()
}

