package com.letta.mobile.data.api

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ApiResultTest : WordSpec({
    "ApiException" should {
        "store code and message" {
            val exception = ApiException(404, "Not Found")
            exception.code shouldBe 404
            exception.message shouldBe "Not Found"
        }

        "extend Exception" {
            ApiException(403, "Forbidden").shouldBeInstanceOf<Exception>()
        }
    }

    "ApiResult.Success" should {
        "wrap simple data" {
            val result = ApiResult.Success("test data")
            result.data shouldBe "test data"
        }

        "wrap complex data" {
            data class TestData(val id: String, val value: Int)
            val payload = TestData("abc", 123)
            val result = ApiResult.Success(payload)
            result.data shouldBe payload
            result.data.id shouldBe "abc"
            result.data.value shouldBe 123
        }

        "be of correct type" {
            val result: ApiResult<String> = ApiResult.Success("test")
            result.shouldBeInstanceOf<ApiResult.Success<String>>()
        }
    }

    "ApiResult.ApiError" should {
        "hold code and message" {
            val result = ApiResult.ApiError(500, "Internal Server Error")
            result.code shouldBe 500
            result.message shouldBe "Internal Server Error"
        }

        "support empty message" {
            val result = ApiResult.ApiError(204, "")
            result.code shouldBe 204
            result.message shouldBe ""
        }

        "be of correct type" {
            val result: ApiResult<Nothing> = ApiResult.ApiError(400, "Bad Request")
            result.shouldBeInstanceOf<ApiResult.ApiError>()
        }
    }

    "ApiResult.NetworkError" should {
        "hold runtime exception" {
            val exception = RuntimeException("Connection failed")
            val result = ApiResult.NetworkError(exception)
            result.exception shouldBe exception
            result.exception.message shouldBe "Connection failed"
        }

        "support different exception types" {
            val ioResult = ApiResult.NetworkError(java.io.IOException("IO failed"))
            val runtimeResult = ApiResult.NetworkError(RuntimeException("Runtime error"))
            ioResult.exception.shouldBeInstanceOf<java.io.IOException>()
            runtimeResult.exception.shouldBeInstanceOf<RuntimeException>()
        }

        "be of correct type" {
            val result: ApiResult<Nothing> = ApiResult.NetworkError(java.io.IOException("Network timeout"))
            result.shouldBeInstanceOf<ApiResult.NetworkError>()
        }
    }
})
