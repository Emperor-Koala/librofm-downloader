package com.vishnurajeevan.libroabs.converter.ffmpeg

import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.Chapter
import com.vishnurajeevan.libroabs.models.libro.Tracks
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFmpegUtils
import net.bramp.ffmpeg.FFprobe
import net.bramp.ffmpeg.builder.FFmpegBuilder
import net.bramp.ffmpeg.probe.FFmpegFormat
import net.bramp.ffmpeg.probe.FFmpegProbeResult
import net.bramp.ffmpeg.progress.Progress
import net.bramp.ffmpeg.progress.ProgressListener
import java.io.BufferedWriter
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class FfmpegClient(
  @Named("ffprobePath") ffprobePath: String,
  private val executor: FFmpegExecutor,
  private val lfdLogger: Logger,
) {

  private val ffprobe = FFprobe(ffprobePath)

  suspend fun convertBookToM4b(book: Book, tracks: List<Tracks>, targetDirectory: File, audioQuality: String) {
    val newFile = File(targetDirectory, "${book.title}.m4b")

    // Download Cover Image
    val coverFile = File(targetDirectory, "cover.jpg")
    downloadCoverImage(book.cover_url, coverFile)

    val chapterFiles =
      targetDirectory.listFiles { file -> file.extension == "mp3" }?.sortedBy { it.nameWithoutExtension }
        ?: emptyList()

    val metadataFile = File(targetDirectory, "metadata.txt")
    generateMetadataFile(book, tracks, chapterFiles, metadataFile)

    // Create a list file to handle spaces in filenames
    val listFile = File(targetDirectory, "input_list.txt").apply {
      bufferedWriter().use { writer ->
        chapterFiles.forEach { file ->
          if (!file.exists()) throw IllegalArgumentException("MP3 file not found: ${file.absolutePath}")
          writer.write(escapeForInputList(file.absolutePath))
        }
      }
    }

    val builder = FFmpegBuilder()
      .addExtraArgs("-f", "concat", "-safe", "0")
      .addExtraArgs("-i", listFile.absolutePath)
      .addExtraArgs("-f", "ffmetadata", "-i", metadataFile.absolutePath)
      .addInput(coverFile.absolutePath)
      .addOutput(newFile.absolutePath)
      .setAudioCodec("aac")
      .addExtraArgs("-b:a", audioQuality)
      .addExtraArgs("-movflags", "faststart")
      .addExtraArgs("-map", "0:a") // Audio from MP3s
      .addExtraArgs("-map_metadata", "1")
      .addExtraArgs("-map", "2:v") // Cover image
      .addExtraArgs("-disposition:v:0", "attached_pic")
      .addExtraArgs("-c:v:0", "mjpeg")
      .addExtraArgs("-id3v2_version", "3")
      .done()

    executor.createJob(builder, object : ProgressListener {
      val durationNs = book.audiobook_info.duration * TimeUnit.SECONDS.toNanos(1)
      override fun progress(progress: Progress) {
        val percentage = progress.out_time_ns.toDouble() / durationNs.toDouble();
        lfdLogger.v(
          String.format(
            "[%.0f%%] status:%s time:%s ms speed:%.2fx",
            percentage * 100,
            progress.status,
            FFmpegUtils.toTimecode(progress.out_time_ns, TimeUnit.NANOSECONDS),
            progress.speed
          )
        )
      }
    }).run()

    //cleanup
    listFile.delete()
    metadataFile.delete()

    lfdLogger.v("M4B file created: ${newFile.absolutePath}")
  }

  private fun downloadCoverImage(coverUrl: String, outputFile: File) {
    val fullUrl = if (coverUrl.startsWith("//")) "https:$coverUrl" else coverUrl

    try {
      URI(fullUrl).toURL().openStream().use { input ->
        Files.copy(input, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      lfdLogger.v("Cover image saved to ${outputFile.absolutePath}")
    } catch (e: Exception) {
      lfdLogger.v("Failed to download cover image: ${e.message}")
    }
  }

  private fun generateMetadataFile(
    book: Book,
    tracks: List<Tracks>,
    chapterFiles: List<File>,
    outputFile: File,
  ) {
    // Extract metadata
    val title = book.title
    val author = book.authors.joinToString(", ")
    val series = book.series
    val publisher = book.publisher
    val publicationDate = book.publication_date
    val genres = book.genres.map { it.name }
    val description = book.description
    val seriesNum = book.series_num

    val chapters = mutableListOf<Chapter>()
    var startTimeMs = 0L

    chapterFiles.forEachIndexed { index, file ->
      val fileInfo = getFileInfo(file)
      val durationMs = getMp3Duration(fileInfo.format)
      val endTimeMs = startTimeMs + durationMs

      // Use either the title from the API, or from the file, or fall back to a generic "Chapter X"
      val chapterTitle = tracks[index].chapter_title?.takeIf { it.isNotBlank() }
        ?: fileInfo.format.tags?.get("title")?.takeIf { it.isNotBlank() }
        ?: "Chapter ${index + 1}"
      chapters.add(Chapter(chapterTitle, startTimeMs, endTimeMs))
      startTimeMs = endTimeMs
    }

    val metadataMap = mapOf(
      "title" to title,
      "artist" to author,
      "album" to "${title}${series?.let { " ($it Book ${seriesNum ?: "X"})" } ?: ""}",
      "genre" to genres.joinToString(", "),
      "date" to publicationDate.toLocalDateTime(TimeZone.Companion.UTC).year.toString(),
      "publisher" to publisher,
      "comment" to description.replace("\n", " ")
    )

    outputFile.bufferedWriter().use { writer ->
      writer.write(";FFMETADATA1\n")
      writeEscapedMetaDataToFile(metadataMap, writer)
      writer.newLine()
      chapters.forEach { chapter ->
        writer.write("[CHAPTER]\n")
        writer.write("TIMEBASE=1/1000\n")
        writer.write("START=${chapter.startTimeMs}\n")
        writer.write("END=${chapter.endTimeMs}\n")
        writer.write("title=\"${escapeMetadataValue(chapter.title)}\"\n")
        writer.newLine()
      }
    }
  }

  suspend fun renameM4bChapters(m4bFile: File, tracks: List<Tracks>) {
    val chapters = getFileInfo(m4bFile).chapters
    if (chapters.isEmpty()) {
      lfdLogger.v("No chapters found in ${m4bFile.name}, skipping chapter rename")
      return
    }

    val sortedTracks = tracks.sortedBy { it.number }
    if (sortedTracks.size != chapters.size || sortedTracks.any { it.chapter_title == null }) {
      lfdLogger.v("Track/chapter mismatch for ${m4bFile.name}, skipping chapter rename")
      return
    }

    val total = sortedTracks.size
    val newTitles = sortedTracks.map { track ->
      "${track.number.toString().padStart(total.toString().length, '0')} - ${track.chapter_title}"
    }

    val metadataFile = File(m4bFile.parentFile, "chapters.txt")
    metadataFile.bufferedWriter().use { writer ->
      writer.write(";FFMETADATA1\n")
      chapters.forEachIndexed { index, chapter ->
        writer.write("[CHAPTER]\n")
        writer.write("TIMEBASE=${chapter.time_base}\n")
        writer.write("START=${chapter.start}\n")
        writer.write("END=${chapter.end}\n")
        writer.write("title=${escapeFfmetadataValue(newTitles[index])}\n")
        writer.newLine()
      }
    }

    val tempFile = File(m4bFile.parentFile, "${m4bFile.nameWithoutExtension}.chapters-tmp.${m4bFile.extension}")

    // input 0 = new chapter titles, input 1 = existing m4b (streams/tags kept, chapters replaced).
    // Map audio/video explicitly (not "-map 1") to skip the muxer's own hidden chapter text track,
    // which otherwise conflicts with the new chapters being mapped in from input 0.
    val builder = FFmpegBuilder()
      .addExtraArgs("-f", "ffmetadata", "-i", metadataFile.absolutePath)
      .addInput(m4bFile.absolutePath)
      .addOutput(tempFile.absolutePath)
      .setAudioCodec("copy")
      .setVideoCodec("copy")
      .addExtraArgs("-map", "1:a")
      .addExtraArgs("-map", "1:v?")
      .addExtraArgs("-disposition:v:0", "attached_pic")
      .addExtraArgs("-map_metadata", "1")
      .addExtraArgs("-map_chapters", "0")
      .done()

    executor.createJob(builder).run()

    metadataFile.delete()
    Files.move(tempFile.toPath(), m4bFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

    lfdLogger.v("Renamed chapters for ${m4bFile.name}")
  }

  private fun getFileInfo(file: File): FFmpegProbeResult {
    return ffprobe.probe(file.absolutePath)
  }

  private fun getMp3Duration(format: FFmpegFormat): Long {
    return TimeUnit.MILLISECONDS.convert(format.duration.toLong(), TimeUnit.SECONDS)
  }

  private fun escapeForInputList(path: String): String {
    return "file '${path.replace("'", "'\\''")}'\n"
  }

  private fun writeEscapedMetaDataToFile(metadata: Map<String, String>, writer: BufferedWriter) {
    metadata.forEach { (key, value) ->
      writer.write("$key=\"${escapeMetadataValue(value)}\"\n")
    }
  }

  private fun escapeMetadataValue(value: String): String {
    return value.replace("\"", "\\\"").trim()  // Replace " with ' and trim spaces
  }

  private fun escapeFfmetadataValue(value: String): String {
    // ffmetadata has no quoting; '=', ';', '#', '\' and newlines must be backslash-escaped instead.
    return value
      .replace("\\", "\\\\")
      .replace("=", "\\=")
      .replace(";", "\\;")
      .replace("#", "\\#")
      .replace("\n", "\\\n")
  }
}