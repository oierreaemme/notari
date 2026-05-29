package com.voicenotemd.core.inference.session

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.dispatchers.AppDispatchers
import com.voicenotemd.core.common.repository.ImportResult
import com.voicenotemd.core.common.repository.ModelImportCandidate
import com.voicenotemd.core.common.repository.OnDeviceModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class FileBasedOnDeviceModelRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var modelDir: File
    private lateinit var targetFile: File
    private lateinit var dispatchers: AppDispatchers

    @Before
    fun setUp() {
        modelDir = tempFolder.newFolder("models")
        targetFile = File(modelDir, "gemma-4-e2b-it.litertlm")
        dispatchers =
            AppDispatchers(
                default = testDispatcher,
                io = testDispatcher,
                main = testDispatcher,
                unconfined = Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        targetFile.delete()
    }

    @Test
    fun `should report Missing initially when file is absent`() =
        runTest {
            val repo = newRepo()

            repo.observeStatus().test {
                assertThat(awaitItem()).isEqualTo(OnDeviceModelStatus.Missing)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should write bytes atomically and flip status to Present on import`() =
        runTest {
            val repo = newRepo()
            val payload = ByteArray(1_024) { (it % 256).toByte() }

            val result = repo.importFrom(ByteArrayInputStream(payload), anyCandidate)

            assertThat(result).isInstanceOf(ImportResult.Success::class.java)
            assertThat(targetFile.exists()).isTrue()
            assertThat(targetFile.readBytes()).isEqualTo(payload)
            assertThat(File(modelDir, "${targetFile.name}.part").exists()).isFalse()

            repo.observeStatus().test {
                assertThat(awaitItem()).isEqualTo(OnDeviceModelStatus.Present)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should not leave a partial file when import stream throws`() =
        runTest {
            val repo = newRepo()
            val brokenStream =
                object : InputStream() {
                    var read = 0

                    override fun read(): Int {
                        if (read >= 16) throw IOException("simulated mid-stream failure")
                        read++
                        return 0xAB
                    }
                }

            val result = repo.importFrom(brokenStream, anyCandidate)

            assertThat(result).isInstanceOf(ImportResult.Failed::class.java)
            assertThat(targetFile.exists()).isFalse()
            assertThat(File(modelDir, "${targetFile.name}.part").exists()).isFalse()
        }

    @Test
    fun `should remove the file and revert status when delete is called`() =
        runTest {
            val repo = newRepo()
            repo.importFrom(ByteArrayInputStream(ByteArray(64)), anyCandidate)
            assertThat(targetFile.exists()).isTrue()

            repo.delete()

            assertThat(targetFile.exists()).isFalse()
            repo.observeStatus().test {
                assertThat(awaitItem()).isEqualTo(OnDeviceModelStatus.Missing)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should reject a file whose name doesn't match before copying`() =
        runTest {
            val repo = newRepo(spec = strictSpec)

            val result =
                repo.importFrom(
                    ByteArrayInputStream(ByteArray(2_000_000)),
                    ModelImportCandidate(displayName = "holiday-photo.jpg", declaredSizeBytes = null),
                )

            assertThat(result).isInstanceOf(ImportResult.Failed::class.java)
            assertThat((result as ImportResult.Failed).reason).contains("doesn't look like")
            // Rejected before any bytes were streamed — no temp, no target.
            assertThat(targetFile.exists()).isFalse()
            assertThat(File(modelDir, "${targetFile.name}.part").exists()).isFalse()
        }

    @Test
    fun `should reject a file that is too small after copying`() =
        runTest {
            val repo = newRepo(spec = strictSpec)

            val result =
                repo.importFrom(
                    ByteArrayInputStream(ByteArray(1_000)),
                    ModelImportCandidate(displayName = "model.litertlm", declaredSizeBytes = null),
                )

            assertThat(result).isInstanceOf(ImportResult.Failed::class.java)
            assertThat((result as ImportResult.Failed).reason).contains("too small")
            assertThat(targetFile.exists()).isFalse()
            assertThat(File(modelDir, "${targetFile.name}.part").exists()).isFalse()
        }

    private fun newRepo(spec: ModelValidationSpec = permissiveSpec): FileBasedOnDeviceModelRepository =
        FileBasedOnDeviceModelRepository(
            modelFileProvider =
                object : ModelFileProvider {
                    override fun isAvailable(): Boolean = targetFile.exists() && targetFile.length() > 0L

                    override fun fileOrNull(): File? = targetFile.takeIf(File::exists)
                },
            targetSelector = ImportTargetSelector { targetFile },
            dispatchers = dispatchers,
            validation = spec,
        )

    private companion object {
        val anyCandidate = ModelImportCandidate(displayName = null, declaredSizeBytes = null)

        // Accepts anything ≥ 1 byte so the existing small-payload tests are unaffected.
        val permissiveSpec =
            ModelValidationSpec(
                label = "the test model",
                expectedHint = "test file",
                minBytes = 1,
                nameMatches = { true },
            )

        // Realistic-shaped spec for the validation tests: requires a .litertlm name and ≥ 1 MB.
        val strictSpec =
            ModelValidationSpec(
                label = "the Gemma model",
                expectedHint = ".litertlm file",
                minBytes = 1_000_000,
                nameMatches = { it.endsWith(".litertlm", ignoreCase = true) },
            )
    }
}
