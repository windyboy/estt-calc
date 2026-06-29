package com.gzzn.airport

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ApplicationTest :
    DescribeSpec({

        describe("Application") {
            it("should have main function") {
                shouldNotThrowAny {
                    val mainMethod = Class.forName("com.gzzn.airport.ApplicationKt")
                        .getDeclaredMethod("main", Array<String>::class.java)
                    mainMethod.shouldNotBeNull()
                }
            }

            it("should have Api object") {
                Api.shouldNotBeNull()
                Api.javaClass.simpleName shouldBe "Api"
            }
        }
    })
