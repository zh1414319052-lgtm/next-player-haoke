package dev.anilbeesetti.nextplayer.feature.player

import dev.anilbeesetti.nextplayer.core.analysis.AnalysisModule
import dev.anilbeesetti.nextplayer.core.domain.DomainModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [DomainModule::class, AnalysisModule::class])
@ComponentScan
class PlayerModule
