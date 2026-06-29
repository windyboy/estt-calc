package com.gzzn.airport.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

class OperationDaysTest :
    DescribeSpec({

        describe("matches") {
            it("matches valid operation days") {
                OperationDays.matches("135", 1).shouldBeTrue()
                OperationDays.matches("135", 3).shouldBeTrue()
                OperationDays.matches("135", 2).shouldBeFalse()
            }

            it("ignores invalid characters and duplicate digits") {
                OperationDays.matches("", 1).shouldBeFalse()
                OperationDays.matches("89", 8).shouldBeFalse()
                OperationDays.matches("111", 1).shouldBeTrue()
                OperationDays.matches("1a2", 1).shouldBeTrue()
                OperationDays.matches("abc", 1).shouldBeFalse()
            }
        }
    })
