package com.voicenotemd.core.inference.di

import android.content.Context
import com.voicenotemd.core.common.dispatchers.AppDispatchers
import com.voicenotemd.core.common.usecase.StructureNoteUseCase
import com.voicenotemd.core.inference.prompt.AssetPromptLoader
import com.voicenotemd.core.inference.prompt.PromptTemplate
import com.voicenotemd.core.inference.schema.StructuredNoteParser
import com.voicenotemd.core.inference.session.GemmaSession
import com.voicenotemd.core.inference.session.LiteRtLmGemmaSession
import com.voicenotemd.core.inference.session.ModelFileProvider
import com.voicenotemd.core.inference.structure.StructureNoteUseCaseImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {
    @Provides
    @Singleton
    fun providePromptTemplate(
        @ApplicationContext context: Context,
    ): PromptTemplate = AssetPromptLoader(context).load(AssetPromptLoader.ACTIVE_PROMPT)

    @Provides
    @Singleton
    fun provideStructuredNoteParser(): StructuredNoteParser = StructuredNoteParser()

    @Provides
    @Singleton
    fun provideGemmaSession(
        @ApplicationContext context: Context,
        dispatchers: AppDispatchers,
        modelFileProvider: ModelFileProvider,
    ): GemmaSession = LiteRtLmGemmaSession(context, dispatchers, modelFileProvider)

    @Provides
    @Singleton
    fun provideStructureNoteUseCase(
        session: GemmaSession,
        prompt: PromptTemplate,
        parser: StructuredNoteParser,
    ): StructureNoteUseCase =
        StructureNoteUseCaseImpl(
            session = session,
            basePrompt = prompt,
            parser = parser,
        )
}
