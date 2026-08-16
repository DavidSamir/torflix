package com.myflix.core.common.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: MyflixDispatcher)

enum class MyflixDispatcher { Default, IO, Main }

/** The application-lifetime coroutine scope used for fire-and-forget work such as progress sync. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
