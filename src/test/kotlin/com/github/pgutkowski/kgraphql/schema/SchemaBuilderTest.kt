package com.github.pgutkowski.kgraphql.schema

import com.github.pgutkowski.kgraphql.Actor
import com.github.pgutkowski.kgraphql.FilmType
import com.github.pgutkowski.kgraphql.Id
import com.github.pgutkowski.kgraphql.KGraphQL
import com.github.pgutkowski.kgraphql.Scenario
import com.github.pgutkowski.kgraphql.defaultSchema
import com.github.pgutkowski.kgraphql.deserialize
import com.github.pgutkowski.kgraphql.expect
import com.github.pgutkowski.kgraphql.extract
import com.github.pgutkowski.kgraphql.schema.model.KQLType
import com.github.pgutkowski.kgraphql.schema.scalar.StringScalarCoercion
import org.hamcrest.CoreMatchers
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import java.util.*
import kotlin.reflect.full.starProjectedType

/**
 * Tests for SchemaBuilder behaviour, not request execution
 */
class SchemaBuilderTest {
    @Test
    fun `DSL created UUID scalar support`(){

        val testedSchema = defaultSchema {
            stringScalar<UUID> {
                description = "unique identifier of object"
                deserialize = { uuid : String -> UUID.fromString(uuid) }
                serialize = UUID::toString
            }
        }

        val uuidScalar = testedSchema.definition.scalars.find { it.name == "UUID" }!!.coercion as StringScalarCoercion<UUID>
        val testUuid = UUID.randomUUID()
        MatcherAssert.assertThat(uuidScalar.serialize(testUuid), CoreMatchers.equalTo(testUuid.toString()))
        MatcherAssert.assertThat(uuidScalar.deserialize(testUuid.toString()), CoreMatchers.equalTo(testUuid))
    }

    @Test
    fun `ignored property DSL`() {
        val testedSchema = defaultSchema {
            query("scenario") {
                resolver { -> Scenario(Id("GKalus", 234234), "Gamil Kalus", "TOO LONG") }
            }
            type<Scenario>{
                Scenario::author.ignore()
                Scenario::content.configure {
                    description = "Content is Content"
                    isDeprecated = false
                }
            }
        }

        val scenarioType = testedSchema.structure.queryTypes[Scenario::class.starProjectedType]
                ?: throw Exception("Scenario type should be present in schema")
        assertThat(scenarioType.properties["author"], nullValue())
        assertThat(scenarioType.properties["content"], notNullValue())
    }

    @Test
    fun `transformation DSL`() {
        val testedSchema = defaultSchema {
            query("scenario") {
                resolver { -> Scenario(Id("GKalus", 234234),"Gamil Kalus", "TOO LONG") }
            }
            type<Scenario> {

                transformation(Scenario::content, { content: String, capitalized : Boolean? ->
                    if(capitalized == true) content.capitalize() else content
                })
            }
        }
        val scenarioType = testedSchema.structure.queryTypes[Scenario::class.starProjectedType]
                ?: throw Exception("Scenario type should be present in schema")
        assert(scenarioType.kqlType is KQLType.Object<*>)
        val kqlType = scenarioType.kqlType as KQLType.Object<*>
        assertThat(kqlType.transformations.getOrNull(0), notNullValue())
    }

    @Test
    fun `extension property DSL`(){
        val testedSchema = defaultSchema {

            query("scenario") {
                resolver { -> Scenario(Id("GKalus", 234234),"Gamil Kalus", "TOO LONG") }
            }

            type<Scenario> {
                property<String>("pdf") {
                    description = "link to pdf representation of scenario"
                    resolver { scenario : Scenario -> "http://scenarios/${scenario.id}" }
                }
            }
        }

        val scenarioType = testedSchema.structure.queryTypes[Scenario::class.starProjectedType]
                ?: throw Exception("Scenario type should be present in schema")
        assert(scenarioType.kqlType is KQLType.Object<*>)
        val kqlType = scenarioType.kqlType as KQLType.Object<*>

        assertThat(kqlType.extensionProperties.getOrNull(0), notNullValue())
        assertThat(scenarioType.properties.keys, hasItem("pdf"))
    }

    @Test
    fun `union type DSL`(){
        val tested = defaultSchema {

            query("scenario") {
                resolver { -> Scenario(Id("GKalus", 234234),"Gamil Kalus", "TOO LONG") }
            }

            val linked = unionType("Linked") {
                type<Actor>()
                type<Scenario>()
            }

            type<Scenario> {
                unionProperty("pdf") {
                    returnType = linked
                    description = "link to pdf representation of scenario"
                    resolver { scenario : Scenario ->
                        if(scenario.author.startsWith("Gamil")){
                            Scenario(Id("ADD", 22), "gambino", "nope")
                        } else{
                            Actor("Chance", 333)
                        }
                    }
                }
            }
        }

        val scenarioType = tested.structure.queryTypes[Scenario::class.starProjectedType]
                ?: throw Exception("Scenario type should be present in schema")
        assert(scenarioType.kqlType is KQLType.Object<*>)
        val unionProperty = scenarioType.unionProperties["pdf"] ?: throw Exception("Scenario should have union property 'pdf'")
        assertThat(unionProperty, notNullValue())
    }

    @Test
    fun `circular dependency extension property`(){
        val tested = defaultSchema {
            query("actor") {
                resolver { -> Actor("Little John", 44) }
            }

            type<Actor> {
                property<Actor>("linked") {
                    resolver { _ -> Actor("BIG John", 3234) }
                }
            }
        }

        val actorType = tested.structure.queryTypes[Actor::class.starProjectedType]
                ?: throw Exception("Scenario type should be present in schema")
        assert(actorType.kqlType is KQLType.Object<*>)
        val property = actorType.properties["linked"] ?: throw Exception("Actor should have ext property 'linked'")
        assertThat(property, notNullValue())
        assertThat(property.returnType.kqlType.name, equalTo("Actor"))
    }

    @Test
    fun ` _ is allowed as receiver argument name`(){
        val schema = defaultSchema {
            query("actor") {
                resolver { -> Actor("Boguś Linda", 4343) }
            }

            type<Actor>{
                property<List<String>>("favDishes") {
                    resolver { _: Actor, size: Int->
                        listOf("steak", "burger", "soup", "salad", "bread", "bird").take(size)
                    }
                }
            }
        }

        deserialize(schema.execute("{actor{favDishes(size: 2)}}"))
    }

    @Test
    fun `Custom type name`(){
        val schema = defaultSchema {
            query("actor") {
                resolver { type: FilmType -> Actor("Boguś Linda $type", 4343)  }
            }

            enum<FilmType> {
                name = "TYPE"
            }
        }

        val result = deserialize(schema.execute("query(\$type : TYPE = FULL_LENGTH){actor(type: \$type){name}}"))
        assertThat(result.extract<String>("data/actor/name"), equalTo("Boguś Linda FULL_LENGTH"))
    }

    private data class LambdaWrapper(val lambda : () -> Int)

    @Test
    fun `function properties cannot be handled`(){
        expect<SchemaException>("Cannot handle function () -> kotlin.Int as Object type"){
            KGraphQL.schema {
                query("lambda"){
                    resolver { -> LambdaWrapper({ 1 }) }
                }
            }
        }
    }

    class InputOne(val string:  String)

    class InputTwo(val one : InputOne)

    @Test
    fun `Schema should map input types`(){
        val schema = defaultSchema {
            inputType<InputTwo>()
        }

        assertThat(schema.inputTypeByKClass(InputOne::class), notNullValue())
        assertThat(schema.inputTypeByKClass(InputTwo::class), notNullValue())
    }

    @Test
    fun `Schema should infer input types from resolver functions`(){
        val schema = defaultSchema {
            query("sample") {
                resolver { i: InputTwo -> "SUCCESS" }
            }
        }

        assertThat(schema.inputTypeByKClass(InputOne::class), notNullValue())
        assertThat(schema.inputTypeByKClass(InputTwo::class), notNullValue())
    }

    @Test
    fun `generic types are not supported`(){
        expect<SchemaException>("Generic types are not supported by GraphQL, found kotlin.Pair<kotlin.Int, kotlin.String>"){
            defaultSchema {
                query("data"){
                    resolver { int: Int, string: String -> int to string }
                }
            }
        }
    }
}