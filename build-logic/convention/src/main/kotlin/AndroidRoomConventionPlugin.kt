import com.google.devtools.ksp.gradle.KspExtension
import com.torfilx.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure<KspExtension> {
            // Exported schemas are committed so every migration is reviewable and testable.
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }

        dependencies {
            add("implementation", libs.findLibrary("room-runtime").get())
            add("implementation", libs.findLibrary("room-ktx").get())
            add("implementation", libs.findLibrary("room-paging").get())
            add("ksp", libs.findLibrary("room-compiler").get())
            add("testImplementation", libs.findLibrary("room-testing").get())
        }
    }
}
