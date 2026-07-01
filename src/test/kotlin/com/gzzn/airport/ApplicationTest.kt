package com.gzzn.airport

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micronaut.runtime.EmbeddedApplication
import io.micronaut.test.extensions.kotest5.annotation.MicronautTest
import jakarta.inject.Inject

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

@MicronautTest(environments = ["test"])
class EsttCalcMicronautTest : DescribeSpec() {

    @Inject
    lateinit var application: EmbeddedApplication<*>

    init {
        xdescribe("Application - disabled") {
            it("should be running") {
                application.isRunning.shouldBeTrue()
            }
        }
    }
}
