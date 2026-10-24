package se.familychat.app.di

import javax.inject.Qualifier

/** The coroutine scope that lives as long as the process (engine workers, foreground observer, notifier). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
