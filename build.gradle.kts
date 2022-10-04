import java.util.*
import org.gradle.api.internal.provider.ValueSourceProviderFactory.ComputationListener
import org.gradle.api.internal.provider.ValueSourceProviderFactory.ValueListener

plugins {
    `java-library`
}

version = "0.1.0-SNAPSHOT"

group = "cloudshift.repro"

/*
   Reproduction of configuration cache issue where a custom ValueSource has its inputs erroneously tracked.

   Run with './gradlew build', twice; on the second run the expected behaviour is to use the configuration cache.

   Actual behaviour is a dirty configuration cache, with this message:

    Calculating task graph as configuration cache cannot be reused because file '../../../.cloudshift/test.properties' has changed.

   The cause of this is using other ValueSources as parameters to your custom ValueSource; it doesn't matter which ones:
     providers.environmentVariable, providers.gradleProperty, etc (could even by another custom ValueSource)

   This is due to a collision of events:
     - Gradle fingerprints the input parameters to the ValueSource;
     - this results in beforeValueObtained() event being fired, which DISABLES input tracking
     - ...and then afterValueObtained() is fired, which ENABLES input tracking, just in time to drop into the obtain()
       method of our custom ValueSource

   Visualized with debug output:

        beforeValueObtained()  // input tracking turned off (entering custom MyValueSource)
        beforeValueObtained()  // input tracking turned off (entering environment variable)
        afterValueObtained()   // input tracking turned on  (exiting environment variable)
        valueObtained(): class org.gradle.api.internal.provider.sources.EnvironmentVariableValueSource org.gradle.api.internal.provider.sources.EnvironmentVariableValueSource$Inject@46d4f344

        // whoops; inside our ValueSource with input tracking turned on
        In value source
        Test file @ /Users/chrislee/.cloudshift/test.properties

        afterValueObtained()   // input tracking turned on (exiting custom MyValueSource)
        valueObtained(): class Build_gradle$MyValueSource Build_gradle$MyValueSource$Inject@6383cdd1

   Relevant code is below.

 */

// capture beforeValueObtained(), afterValueObtained() and valueObtained() events
val controller = objects.newInstance(MyController::class)
controller.listenerManager.addListener(MyListener())

// this provider is implemented as a ValueSource
val ciBuildStatusProvider = providers.environmentVariable("CI").map { true }.orElse(false)

val provider = providers.of(MyValueSource::class) {
    parameters {
        // commenting this out fixes the issue
        // workaround would be to duplicate the logic inside the ValueSource w/o using other ValueSources
        this.ciBuildStatus.set(ciBuildStatusProvider)
    }
}

// resolve provider (when this is done is irrelevant, so long as it happens)
provider.get()

abstract class MyValueSource : ValueSource<String, MyValueSource.MyValueSourceParams> {

    private val logger = Logging.getLogger(MyValueSource::class.java)

    interface MyValueSourceParams : ValueSourceParameters {
        val ciBuildStatus: Property<Boolean>
    }

    override fun obtain(): String {
        logger.lifecycle("In value source")
        // we don't have to resolve the parameter in obtain() to observe the issue; it's resolved
        // implicitly when the parameters are fingerprinted

        //val ciBuildStatus = parameters.ciBuildStatus.get()

        val homeDir = System.getProperty("user.home")!!
        val gradleDir = File(homeDir, ".cloudshift")
        gradleDir.mkdirs()

        val testFile = File(gradleDir, "test.properties")

        logger.lifecycle("Test file @ $testFile")

        testFile.createNewFile()
        val props = Properties()
        testFile.reader().use {
            props.load(it)
        }

        // force the file to change every time through
        props.setProperty("value", System.currentTimeMillis().toString())
        testFile.writer().use {
            props.store(it, "")
        }

        return ""
    }
}


abstract class MyController {
    @get:Inject
    abstract val services : BuildServiceRegistry

    @get:Inject
    abstract val listenerManager : org.gradle.internal.event.ListenerManager

}
class MyListener : ValueListener,
    ComputationListener {
    private val logger = Logging.getLogger(MyListener::class.java)
    override fun <T : Any?, P : ValueSourceParameters?> valueObtained(
        obtainedValue: ValueListener.ObtainedValue<T, P>,
        source: ValueSource<T, P>
    ) {
        logger.lifecycle("valueObtained(): ${obtainedValue.valueSourceType} ${source}")
    }

    override fun beforeValueObtained() {
        logger.lifecycle("beforeValueObtained()")
    }

    override fun afterValueObtained() {
        logger.lifecycle("afterValueObtained()")
    }

}
