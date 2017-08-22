package de.ph1b.audiobook.features.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import com.squareup.picasso.Picasso
import dagger.Reusable
import de.ph1b.audiobook.Book
import de.ph1b.audiobook.R
import de.ph1b.audiobook.features.MainActivity
import de.ph1b.audiobook.misc.dpToPxRounded
import de.ph1b.audiobook.misc.drawable
import de.ph1b.audiobook.misc.getOnUiThread
import de.ph1b.audiobook.misc.value
import de.ph1b.audiobook.persistence.BookRepository
import de.ph1b.audiobook.persistence.PrefsManager
import de.ph1b.audiobook.playback.PlayStateManager
import de.ph1b.audiobook.playback.utils.ServiceController
import de.ph1b.audiobook.uitools.CoverReplacement
import de.ph1b.audiobook.uitools.ImageHelper
import de.ph1b.audiobook.uitools.maxImageSize
import timber.log.Timber
import javax.inject.Inject


@Reusable
class WidgetUpdater @Inject constructor(
    private val context: Context,
    private val repo: BookRepository,
    private val prefs: PrefsManager,
    private val imageHelper: ImageHelper,
    private val serviceController: ServiceController,
    private val playStateManager: PlayStateManager
) {

  private val appWidgetManager = AppWidgetManager.getInstance(context)

  fun update() {
    val book = repo.bookById(prefs.currentBookId.value)
    Timber.i("update with book ${book?.name}")
    val componentName = ComponentName(context, BaseWidgetProvider::class.java)
    val ids = appWidgetManager.getAppWidgetIds(componentName)

    for (widgetId in ids) {
      updateWidgetForId(book, widgetId)
    }
  }

  private fun updateWidgetForId(book: Book?, widgetId: Int) {
    if (book != null) {
      initWidgetForPresentBook(widgetId, book)
    } else {
      initWidgetForAbsentBook(widgetId)
    }
  }

  private fun initWidgetForPresentBook(widgetId: Int, book: Book) {
    val opts = appWidgetManager.getAppWidgetOptions(widgetId)
    val useWidth = widgetWidth(opts)
    val useHeight = widgetHeight(opts)

    val remoteViews = RemoteViews(context.packageName, R.layout.widget)
    initElements(remoteViews = remoteViews, book = book, coverSize = useHeight)

    if (useWidth > 0 && useHeight > 0) {
      setVisibilities(remoteViews, useWidth, useHeight, book.chapters.size == 1)
    }
    appWidgetManager.updateAppWidget(widgetId, remoteViews)
  }

  private fun widgetWidth(opts: Bundle): Int {
    val key = if (isPortrait) {
      AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
    } else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
    val dp = opts.getInt(key)
    return context.dpToPxRounded(dp.toFloat())
  }

  private fun widgetHeight(opts: Bundle): Int {
    val key = if (isPortrait) {
      AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
    } else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
    val dp = opts.getInt(key)
    return context.dpToPxRounded(dp.toFloat())
  }

  private fun initWidgetForAbsentBook(widgetId: Int) {
    val remoteViews = RemoteViews(context.packageName, R.layout.widget)
    // directly going back to bookChoose
    val wholeWidgetClickI = Intent(context, MainActivity::class.java)
    wholeWidgetClickI.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    val wholeWidgetClickPI = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(),
        wholeWidgetClickI, PendingIntent.FLAG_UPDATE_CURRENT)
    val cover = imageHelper.drawableToBitmap(
        drawable = context.drawable(R.drawable.icon_108dp),
        width = imageHelper.smallerScreenSize,
        height = imageHelper.smallerScreenSize
    )
    remoteViews.setImageViewBitmap(R.id.imageView, cover)
    remoteViews.setOnClickPendingIntent(R.id.wholeWidget, wholeWidgetClickPI)
    appWidgetManager.updateAppWidget(widgetId, remoteViews)
  }

  private val isPortrait: Boolean
    get() {
      val orientation = context.resources.configuration.orientation
      val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      val display = window.defaultDisplay

      @Suppress("DEPRECATION")
      val displayWidth = display.width
      @Suppress("DEPRECATION")
      val displayHeight = display.height

      return orientation != Configuration.ORIENTATION_LANDSCAPE && (orientation == Configuration.ORIENTATION_PORTRAIT || displayWidth == displayHeight || displayWidth < displayHeight)
    }

  private fun initElements(remoteViews: RemoteViews, book: Book, coverSize: Int) {
    val playPauseI = serviceController.getPlayPauseIntent()
    val playPausePI = PendingIntent.getService(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playPauseI, PendingIntent.FLAG_UPDATE_CURRENT)
    remoteViews.setOnClickPendingIntent(R.id.playPause, playPausePI)

    val fastForwardI = serviceController.getFastForwardIntent()
    val fastForwardPI = PendingIntent.getService(context, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, fastForwardI, PendingIntent.FLAG_UPDATE_CURRENT)
    remoteViews.setOnClickPendingIntent(R.id.fastForward, fastForwardPI)

    val rewindI = serviceController.getRewindIntent()
    val rewindPI = PendingIntent.getService(context, KeyEvent.KEYCODE_MEDIA_REWIND,
        rewindI, PendingIntent.FLAG_UPDATE_CURRENT)
    remoteViews.setOnClickPendingIntent(R.id.rewind, rewindPI)

    if (playStateManager.playState === PlayStateManager.PlayState.PLAYING) {
      remoteViews.setImageViewResource(R.id.playPause, R.drawable.ic_pause_white_36dp)
    } else {
      remoteViews.setImageViewResource(R.id.playPause, R.drawable.ic_play_white_36dp)
    }

    // if we have any book, init the views and have a click on the whole widget start BookPlay.
    // if we have no book, simply have a click on the whole widget start BookChoose.
    remoteViews.setTextViewText(R.id.title, book.name)
    val name = book.currentChapter().name

    remoteViews.setTextViewText(R.id.summary, name)

    val wholeWidgetClickI = MainActivity.goToBookIntent(context, book.id)
    val wholeWidgetClickPI = PendingIntent.getActivity(
        context,
        System.currentTimeMillis().toInt(),
        wholeWidgetClickI,
        PendingIntent.FLAG_UPDATE_CURRENT
    )

    var cover = if (book.coverFile().canRead() && book.coverFile().length() < maxImageSize) {
      val sizeForPicasso = coverSize.takeIf { it > 0 }
          ?: context.dpToPxRounded(56F)
      Picasso.with(context)
          .load(book.coverFile())
          .resize(sizeForPicasso, sizeForPicasso)
          .getOnUiThread()
    } else null


    if (cover == null) {
      val coverReplacement = CoverReplacement(book.name, context)
      cover = imageHelper.drawableToBitmap(coverReplacement, imageHelper.smallerScreenSize, imageHelper.smallerScreenSize)
    }

    remoteViews.setImageViewBitmap(R.id.imageView, cover)
    remoteViews.setOnClickPendingIntent(R.id.wholeWidget, wholeWidgetClickPI)
  }

  private fun setVisibilities(remoteViews: RemoteViews, width: Int, height: Int, singleChapter: Boolean) {
    setHorizontalVisibility(remoteViews, width, height)
    setVerticalVisibility(remoteViews, height, singleChapter)
  }

  private fun setHorizontalVisibility(remoteViews: RemoteViews, widgetWidth: Int, coverSize: Int) {
    val singleButtonSize = context.dpToPxRounded(8F + 36F + 8F)
    // widget height because cover is square
    var summarizedItemWidth = 3 * singleButtonSize + coverSize

    // set all views visible
    remoteViews.setViewVisibility(R.id.imageView, View.VISIBLE)
    remoteViews.setViewVisibility(R.id.rewind, View.VISIBLE)
    remoteViews.setViewVisibility(R.id.fastForward, View.VISIBLE)

    // hide cover if we need space
    if (summarizedItemWidth > widgetWidth) {
      remoteViews.setViewVisibility(R.id.imageView, View.GONE)
      summarizedItemWidth -= coverSize
    }

    // hide fast forward if we need space
    if (summarizedItemWidth > widgetWidth) {
      remoteViews.setViewVisibility(R.id.fastForward, View.GONE)
      summarizedItemWidth -= singleButtonSize
    }

    // hide rewind if we need space
    if (summarizedItemWidth > widgetWidth) {
      remoteViews.setViewVisibility(R.id.rewind, View.GONE)
    }
  }

  private fun setVerticalVisibility(remoteViews: RemoteViews, widgetHeight: Int, singleChapter: Boolean) {
    val buttonSize = context.dpToPxRounded(8F + 36F + 8F)
    val titleSize = context.resources.getDimensionPixelSize(R.dimen.list_text_primary_size)
    val summarySize = context.resources.getDimensionPixelSize(R.dimen.list_text_secondary_size)

    var summarizedItemsHeight = buttonSize + titleSize + summarySize

    // first setting all views visible
    remoteViews.setViewVisibility(R.id.summary, View.VISIBLE)
    remoteViews.setViewVisibility(R.id.title, View.VISIBLE)

    // when we are in a single chapter or we are to high, hide summary
    if (singleChapter || widgetHeight < summarizedItemsHeight) {
      remoteViews.setViewVisibility(R.id.summary, View.GONE)
      summarizedItemsHeight -= summarySize
    }

    // if we ar still to high, hide title
    if (summarizedItemsHeight > widgetHeight) {
      remoteViews.setViewVisibility(R.id.title, View.GONE)
    }
  }
}
