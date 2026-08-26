package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.project.Project
import org.mockito.Mockito
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class NewMethodContextServiceTest {
    private val service = NewMethodContextService(Mockito.mock(Project::class.java))

    private fun state(
        document: String,
        cursor: Int,
    ): EditorState {
        val line = document.substring(0, cursor).count { it == '\n' } + 1
        val lineStart = document.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
        val lineCount = document.lines().size
        return EditorState(
            documentText = document,
            line = line,
            cursorOffset = cursor,
            filePath = "test.ts",
            documentLineCount = lineCount,
            currentLinePrefix = document.substring(lineStart, cursor),
        )
    }

    private val facade =
        """
        import {Injectable} from "@nestjs/common";
        import {InjectRepository} from "@nestjs/typeorm";
        import {In, Repository} from "typeorm";

        import {UserRole} from "~/src/graphql";
        import {ProductAmountMap} from "~/src/map/productAmountMap";
        import {CartProductLimitationEntity} from "~/src/module/model/entity/cartProductLimitation.entity";
        import {PropertyFacade} from "~/src/module/model/facade/property.facade";

        @Injectable()
        export class CartProductLimitationFacade {
        	public constructor(
        		@InjectRepository(CartProductLimitationEntity)
        		private readonly cartProductLimitationRepository: Repository<CartProductLimitationEntity>,
        		private readonly propertyFacade: PropertyFacade,
        	) {
        	}

        	public async findByProductIds(productIds: number[]): Promise<CartProductLimitationEntity[]> {
        		return await this.cartProductLimitationRepository.find({
        			where: {productId: In(productIds)},
        		});
        	}

        	public async findByBannedRoles(bannedRoles: readonly UserRole[]): Promise<CartProductLimitationEntity[]> {
        		return await this.cartProductLimitationRepository.find({
        			where: {banRoles: In(bannedRoles)},
        		});
        	}

        	public async getEffectiveMaximumAmountInCartMap(
        		productIds: number[],
        	): Promise<Map<number, number>> {
        		return new Map();
        	}
        }
        """.trimIndent().replace("    ", "\t")

    @Test
    fun `detects typing findBy below findByProductIds`() {
        val line = "\tpublic async findBy"
        val idx = facade.indexOf("\tpublic async getEffectiveMaximumAmountInCartMap(")
        val doc = facade.substring(0, idx) + line + "\n\n" + facade.substring(idx + 1)
        val cursor = doc.indexOf("\tpublic async findBy\n") + line.length

        val context = service.detectTextOnly(state(doc, cursor))

        assertEquals("findBy", context?.typedName)
        assertEquals(listOf("findByProductIds", "findByBannedRoles"), context?.siblingMethodNames)
        assertEquals(
            listOf("findByProductIds", "findByBannedRoles", "getEffectiveMaximumAmountInCartMap"),
            context?.allMethodNames,
        )
    }

    @Test
    fun `no detection when prefix does not collide`() {
        val line = "\tpublic async computeMax"
        val idx = facade.indexOf("\tpublic async getEffectiveMaximumAmountInCartMap(")
        val doc = facade.substring(0, idx) + line + "\n\n" + facade.substring(idx + 1)
        val cursor = doc.indexOf("\tpublic async computeMax\n") + line.length

        assertNull(service.detectTextOnly(state(doc, cursor)))
    }

    @Test
    fun `no detection when caret is inside an existing method name`() {
        // Caret right after `findBy` inside the existing findByProductIds declaration
        val prefix = "\tpublic async findBy"
        val cursor = facade.indexOf(prefix) + prefix.length

        assertNull(service.detectTextOnly(state(facade, cursor)))
    }

    @Test
    fun `no detection inside a method body`() {
        val bodyIdx = facade.indexOf("return await this.cartProductLimitationRepository")
        val insert = "\t\tconst x = findBy"
        val doc = facade.substring(0, bodyIdx) + insert + "\n" + facade.substring(bodyIdx)
        val cursor = bodyIdx + insert.length

        assertNull(service.detectTextOnly(state(doc, cursor)))
    }

    @Test
    fun `no detection for keywords and short names`() {
        val line = "\tpublic async re"
        val idx = facade.indexOf("\tpublic async getEffectiveMaximumAmountInCartMap(")
        val doc = facade.substring(0, idx) + line + "\n\n" + facade.substring(idx + 1)
        val cursor = doc.indexOf("\tpublic async re\n") + line.length

        assertNull(service.detectTextOnly(state(doc, cursor)))
    }

    @Test
    fun `no detection when previous member is not closed`() {
        // typing below an unclosed method signature (no closing brace above)
        val idx = facade.indexOf("\tpublic async getEffectiveMaximumAmountInCartMap(")
        val doc =
            facade.substring(0, idx) +
                "\tpublic async getLimit(" +
                "\n\t\tpublic async findBy" +
                "\n\n" + facade.substring(idx + 1)
        val cursor = doc.indexOf("\tpublic async findBy\n") + "\tpublic async findBy".length

        assertNull(service.detectTextOnly(state(doc, cursor)))
    }

    @Test
    fun `steering mentions siblings and derived method names`() {
        val context =
            NewMethodContextService.NewMethodContext(
                typedName = "findBy",
                siblingMethodNames = listOf("findByProductIds"),
                uncoveredEntityFields = listOf("banRoles", "incompatibleProductIds"),
            )
        val steering = service.buildSteering(context)

        assertTrue(steering.contains("findByProductIds"))
        assertTrue(steering.contains("findByBanRoles using the banRoles entity field"))
        assertTrue(steering.contains("findByIncompatibleProductIds using the incompatibleProductIds entity field"))
    }

    @Test
    fun `steering falls back without entity fields`() {
        val context =
            NewMethodContextService.NewMethodContext(
                typedName = "findBy",
                siblingMethodNames = listOf("findByProductIds"),
            )
        val steering = service.buildSteering(context)

        assertTrue(steering.contains("findByProductIds"))
        assertTrue(steering.contains("near-duplicate"))
    }

    @Test
    fun `word matching handles plurals prefixes and equality`() {
        // equality / plural / prefix
        assertTrue(service.wordMatches("roles", "roles"))
        assertTrue(service.wordMatches("id", "ids"))
        assertTrue(service.wordMatches("ban", "banned"))
        assertTrue(service.wordMatches("banned", "ban"))
        // unrelated words
        assertEquals(false, service.wordMatches("product", "incompatible"))
        assertEquals(false, service.wordMatches("by", "banned"))
        assertEquals(false, service.wordMatches("find", "roles"))
    }

    @Test
    fun `word splitting handles camel pascal and snake case`() {
        assertEquals(listOf("find", "by", "banned", "roles"), service.wordsOf("findByBannedRoles"))
        assertEquals(listOf("cart", "product", "limitation"), service.wordsOf("cart_product_limitation"))
        assertEquals(listOf("incompatible", "product", "ids"), service.wordsOf("incompatibleProductIds"))
    }

    @Test
    fun `near-duplicate names collide unrelated do not`() {
        assertTrue(service.nameCollides("findByBanRoles", listOf("findByBannedRoles")))
        assertTrue(service.nameCollides("findByBannedRoles", listOf("findByBannedRoles")))
        assertEquals(
            false,
            service.nameCollides(
                "findByIncompatibleProductIds",
                listOf("findByProductIds", "findByBannedRoles"),
            ),
        )
    }

    @Test
    fun `field coverage is word-level not substring`() {
        // banRoles IS covered by findByBannedRoles (substring match would miss it)
        assertTrue(service.fieldCoveredByMethod("banRoles", "findByBannedRoles"))
        assertTrue(service.fieldCoveredByMethod("productId", "findByProductIds"))
        assertEquals(false, service.fieldCoveredByMethod("incompatibleProductIds", "findByProductIds"))
        assertEquals(false, service.fieldCoveredByMethod("banRoles", "findByProductIds"))
    }

    @Test
    fun `completion duplicate detection with half-typed names`() {
        val document =
            "\tpublic async findByBann" // typed prefix in the document
        // completion continues the half-typed identifier
        assertTrue(
            service.completionDuplicatesExistingMethod(
                documentText = document,
                startIndex = document.length,
                completion = "edRoles(bannedRoles: readonly UserRole[]): Promise<CartProductLimitationEntity[]> {",
                existingMethods = listOf("findByProductIds", "findByBannedRoles"),
            ),
        )
        // a genuinely new method passes
        assertEquals(
            false,
            service.completionDuplicatesExistingMethod(
                documentText = "\tpublic async findBy",
                startIndex = "\tpublic async findBy".length,
                completion = "IncompatibleProductIds(incompatibleProductIds: number[]): Promise<CartProductLimitationEntity[]> {",
                existingMethods = listOf("findByProductIds", "findByBannedRoles"),
            ),
        )
        // method bodies are not treated as declarations
        assertEquals(
            false,
            service.completionDuplicatesExistingMethod(
                documentText = "\t\t",
                startIndex = 2,
                completion = "return await this.cartProductLimitationRepository.find({\n\t\t\twhere: {productId},\n\t\t});\n",
                existingMethods = listOf("findByProductIds"),
            ),
        )
    }

    @Test
    fun `type candidates prefer constructor and field declarations`() {
        val candidates = service.extractTypeCandidates(facade)

        // CartProductLimitationEntity from the constructor parameter type comes first;
        // Repository is blocklisted, UserRole appears later in method bodies.
        assertTrue(candidates.first() == "CartProductLimitationEntity")
        assertTrue(candidates.contains("PropertyFacade"))
        assertEquals(false, candidates.contains("Repository"))
    }
}
