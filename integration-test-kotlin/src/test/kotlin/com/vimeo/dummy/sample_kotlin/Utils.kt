package com.vimeo.dummy.sample_kotlin

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.vimeo.sample_kotlin.stag.generated.Stag
import org.junit.Assert.*
import uk.co.jemos.podam.api.PodamFactoryImpl
import kotlin.reflect.KClass

/**
 * Unit test utilities.
 *
 * Created by restainoa on 5/8/17.
 */
class Utils {

    companion object Utils {

        private fun <T : Any> getTypeAdapter(clazz: KClass<T>): TypeAdapter<T>? {
            val gson = Gson()
            val factory = Stag.Factory()
            val innerModelType = TypeToken.get(clazz.java)
            return factory.create(gson, innerModelType)
        }

        /**
         * Verifies that a TypeAdapter was generated for the specified class.

         * @param clazz the class to check.
         * *
         * @param <T>   the type of the class, used internally.
         * *
         * @throws Exception throws an exception if an adapter was not generated.
        </T> */
        @Throws(Exception::class)
        fun <T : Any> verifyTypeAdapterGeneration(clazz: KClass<T>) {
            val typeAdapter = getTypeAdapter(clazz)
            assertNotNull("Type adapter should have been generated by Stag", typeAdapter)
        }

        /**
         * Verifies that a TypeAdapter was NOT generated for the specified class.

         * @param clazz the class to check.
         * *
         * @param <T>   the type of the class, used internally.
         * *
         * @throws Exception throws an exception if an adapter was generated.
        </T> */
        @Throws(Exception::class)
        fun <T : Any> verifyNoTypeAdapterGeneration(clazz: KClass<T>) {
            val typeAdapter = getTypeAdapter(clazz)
            assertNull("Type adapter should not have been generated by Stag", typeAdapter)
        }

        /**
         * Verifies that the type adapter for [clazz] is correct. It does this by manufacturing an
         * instance of the class, writing it to JSON, and then reading that object back out of JSON
         * and comparing the two instances.
         *
         * @param clazz the [KClass] to use to get the [TypeAdapter].
         */
        fun <T : Any> verifyTypeAdapterCorrectness(clazz: KClass<T>) {
            val factory = PodamFactoryImpl()

            val obj: T = factory.manufacturePojo<T>(clazz.java)
            val typeAdapter: TypeAdapter<T>? = getTypeAdapter(clazz)

            val json = typeAdapter?.toJson(obj)

            assertEquals(obj, typeAdapter?.fromJson(json))
        }

    }
}
