package com.voicenotemd.core.inference.session

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.dispatchers.AppDispatchers
import com.voicenotemd.core.common.repository.ImportResult
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

            val result = repo.importFrom(ByteArrayInputStream(payload))

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

            val result = repo.importFrom(brokenStream)

            assertThat(result).isInstanceOf(ImportResult.Failed::class.java)
            assertThat(targetFile.exists()).isFalse()
            assertThat(File(modelDir, "${targetFile.name}.part").exists()).isFalse()
        }

    @Test
    fun `should remove the file and revert status when delete is called`() =
        runTest {
            val repo = newRepo()
            repo.importFrom(ByteArrayInputStream(ByteArray(64)))
            assertThat(targetFile.exists()).isTrue()

            repo.delete()

            assertThat(targetFile.exists()).isFalse()
            repo.observeStatus().test {
                assertThat(awaitItem()).isEqualTo(OnDeviceModelStatus.Missing)
                cancelAndConsumeRemainingEvents()
            }
        }

    private fun newRepo(): FileBasedOnDeviceModelRepository =
        FileBasedOnDeviceModelRepository(
            modelFileProvider =
                object : ModelFileProvider {
                    override fun isAvailable(): Boolean = targetFile.exists() && targetFile.length() > 0L

                    override fun fileOrNull(): File? = targetFile.takeIf(File::exists)
                },
            targetSelector = ImportTargetSelector { targetFile },
            dispatchers = dispatchers,
        )
}
