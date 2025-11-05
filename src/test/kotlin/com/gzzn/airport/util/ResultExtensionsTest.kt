package com.gzzn.airport.util

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ResultExtensionsTest : DescribeSpec({

    describe("flatMap") {
        it("should chain successful results") {
            val result = Result.success(5)
                .flatMap { Result.success(it * 2) }
                .flatMap { Result.success(it + 10) }
            
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe 20
        }
        
        it("should propagate failure from first operation") {
            val exception = RuntimeException("First error")
            val result = Result.failure<Int>(exception)
                .flatMap { Result.success(it * 2) }
                .flatMap { Result.success(it + 10) }
            
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull() shouldBe exception
        }
        
        it("should propagate failure from middle operation") {
            val exception = RuntimeException("Middle error")
            val result = Result.success(5)
                .flatMap { Result.failure<Int>(exception) }
                .flatMap { Result.success(it + 10) }
            
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull() shouldBe exception
        }
        
        it("should handle null values correctly") {
            val result = Result.success(null as String?)
                .flatMap { value ->
                    if (value == null) Result.success("default")
                    else Result.success(value.uppercase())
                }
            
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe "default"
        }
        
        it("should maintain exception type through chain") {
            val exception = IllegalArgumentException("Invalid argument")
            val result = Result.success(5)
                .flatMap { Result.failure<Int>(exception) }
            
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        }
    }
    
    describe("mapNotNull") {
        it("should transform non-null values") {
            val result: Result<String?> = Result.success("hello")
            val transformed = result.mapNotNull { it.uppercase() }
            
            transformed.isSuccess.shouldBeTrue()
            transformed.getOrNull() shouldBe "HELLO"
        }
        
        it("should handle null values") {
            val result: Result<String?> = Result.success(null)
            val transformed = result.mapNotNull { it.uppercase() }
            
            transformed.isSuccess.shouldBeTrue()
            transformed.getOrNull().shouldBeNull()
        }
        
        it("should propagate failure") {
            val exception = RuntimeException("Error")
            val result: Result<String?> = Result.failure(exception)
            val transformed = result.mapNotNull { it.uppercase() }
            
            transformed.isFailure.shouldBeTrue()
            transformed.exceptionOrNull() shouldBe exception
        }
        
        it("should work with nullable complex types") {
            data class User(val name: String, val age: Int)
            
            val result: Result<User?> = Result.success(User("John", 30))
            val nameResult = result.mapNotNull { it.name }
            
            nameResult.isSuccess.shouldBeTrue()
            nameResult.getOrNull() shouldBe "John"
        }
        
        it("should preserve null through transformation chain") {
            val result: Result<String?> = Result.success(null)
            val transformed = result
                .mapNotNull { it.uppercase() }
                .mapNotNull { it.reversed() }
            
            transformed.isSuccess.shouldBeTrue()
            transformed.getOrNull().shouldBeNull()
        }
    }
    
    describe("flatMap with mapNotNull combination") {
        it("should work together in a chain") {
            data class User(val name: String?)
            
            val getUserResult: Result<User?> = Result.success(User("Alice"))
            
            val result = getUserResult
                .mapNotNull { it.name }  // Extract name
                .flatMap { name ->  // Use flatMap to chain another Result operation
                    if (name != null && name.length > 3) {
                        Result.success(name.uppercase())
                    } else {
                        Result.failure(IllegalArgumentException("Name too short"))
                    }
                }
            
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe "ALICE"
        }
        
        it("should handle failure in combination") {
            data class User(val name: String?)
            
            val getUserResult: Result<User?> = Result.success(User(null))
            
            val result = getUserResult
                .mapNotNull { it.name }
                .flatMap { name ->
                    if (name != null) {
                        Result.success(name.uppercase())
                    } else {
                        Result.failure(IllegalArgumentException("Name is null"))
                    }
                }
            
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        }
    }
})

