package dev.zacsweers.autoservice.ksp

import com.google.auto.service.AutoService
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Sets
import com.google.devtools.ksp.closestClassDeclaration
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import java.io.IOException
import java.util.SortedSet

@AutoService(SymbolProcessor::class)
public class AutoServiceSymbolProcessor : SymbolProcessor {

  private companion object {
    val AUTO_SERVICE_NAME = AutoService::class.qualifiedName!!
  }

  /**
   * Maps the class names of service provider interfaces to the
   * class names of the concrete classes which implement them plus their KSFile (for incremental
   * processing).
   *
   * For example,
   * ```
   * "com.google.apphosting.LocalRpcService" -> "com.google.apphosting.datastore.LocalDatastoreService"
   * ```
   */
  private val providers: Multimap<String, Pair<String, KSFile>> = HashMultimap.create()

  private lateinit var codeGenerator: CodeGenerator
  private lateinit var logger: KSPLogger
  private var verify = false
  private var verbose = false

  override fun init(
    options: Map<String, String>,
    kotlinVersion: KotlinVersion,
    codeGenerator: CodeGenerator,
    logger: KSPLogger
  ) {
    this.codeGenerator = codeGenerator
    this.logger = logger
    verify = options["autoserviceKsp.verify"]?.toBoolean() == true
    verbose = options["autoserviceKsp.verbose"]?.toBoolean() == true
  }

  /**
   * - For each class annotated with [AutoService
   *   - Verify the [AutoService] interface value is correct
   *   - Categorize the class by its service interface
   * - For each [AutoService] interface
   *   - Create a file named `META-INF/services/<interface>`
   *   - For each [AutoService] annotated class for this interface
   *     - Create an entry in the file
   */
  override fun process(resolver: Resolver): List<KSAnnotated> {
    val autoServiceType = resolver.getClassDeclarationByName(
      resolver.getKSNameFromString(AUTO_SERVICE_NAME))
      ?.asType(emptyList())
      ?: error("@AutoService type not found on the classpath.")

    resolver.getSymbolsWithAnnotation(AUTO_SERVICE_NAME)
      .asSequence()
      .filterIsInstance<KSClassDeclaration>()
      .forEach { providerImplementer ->
        val annotation = providerImplementer.annotations.find { it.annotationType.resolve() == autoServiceType }
          ?: error("@AutoService annotation not found")

        @Suppress("UNCHECKED_CAST")
        val providerInterfaces = annotation.arguments
          .find { it.name?.getShortName() == "value" }!!
          .value as? List<KSType>
          ?: error("No 'value' member value found!")

        if (providerInterfaces.isEmpty()) {
          error("No service interfaces provided for element! $providerImplementer")
        }

        for (providerType in providerInterfaces) {
          val providerDecl = providerType.declaration.closestClassDeclaration()!!
          if (checkImplementer(providerImplementer, providerType)) {
            providers.put(providerDecl.toBinaryName(), providerImplementer.toBinaryName() to providerImplementer.containingFile!!)
          } else {
            val message = "ServiceProviders must implement their service provider interface. " +
              providerImplementer.qualifiedName +
              " does not implement " +
              providerDecl.qualifiedName
            error(message)
          }
        }
      }
    generateAndClearConfigFiles()
    return emptyList()
  }

  private fun checkImplementer(
    providerImplementer: KSClassDeclaration,
    providerType: KSType
  ): Boolean {
    if (!verify) {
      return true
    }
    return providerImplementer.getAllSuperTypes().any { it.isAssignableFrom(providerType) }
  }

  private fun generateAndClearConfigFiles() {
    for (providerInterface in providers.keySet()) {
      val resourceFile = "META-INF/services/$providerInterface"
      log("Working on resource file: $resourceFile")
      try {
        val allServices: SortedSet<String> = Sets.newTreeSet()
        val foundImplementers = providers[providerInterface]
        val newServices: Set<String> = HashSet(foundImplementers.map { it.first })
        allServices.addAll(newServices)
        log("New service file contents: $allServices")
        val ksFiles = foundImplementers.map { it.second }
        log("Originating files: ${ksFiles.map(KSFile::fileName)}")
        val dependencies = Dependencies(true, *ksFiles.toTypedArray())
        codeGenerator.createNewFile(
          dependencies,
          "",
          resourceFile,
          ""
        ).bufferedWriter().use { writer ->
          for (service in allServices) {
            writer.write(service)
            writer.newLine()
          }
        }
        log("Wrote to: $resourceFile")
      } catch (e: IOException) {
        error("Unable to create $resourceFile, $e")
      }
    }
    providers.clear()
  }

  private fun log(message: String) {
    if (verbose) {
      logger.logging(message)
    }
  }

  /**
   * Returns the binary name of a reference type. For example,
   * {@code com.google.Foo$Bar}, instead of {@code com.google.Foo.Bar}.
   */
  private fun KSClassDeclaration.toBinaryName(): String {
    return toClassName().reflectionName()
  }

  private fun KSClassDeclaration.toClassName(): ClassName {
    require(!isLocal()) {
      "Local/anonymous classes are not supported!"
    }
    val pkgName = packageName.asString()
    val typesString = qualifiedName!!.asString().removePrefix("$pkgName.")

    val simpleNames = typesString
      .split(".")
    return ClassName(pkgName, simpleNames)
  }

  override fun finish() {

  }
}