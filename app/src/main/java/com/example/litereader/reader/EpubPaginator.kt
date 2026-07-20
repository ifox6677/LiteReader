package com.example.litereader.reader

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.litereader.domain.model.Block
import com.example.litereader.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * 一页内容
 */
data class PageContent(
    val chapterTitle: String,
    val blocks: List<Block>
)


/**
 * EPUB分页入口
 *
 * 负责：
 * 1. 参数转换
 * 2. 图片尺寸预读取
 * 3. 调用 TextLayoutEngine
 *
 * 具体文字分页由 TextLayoutEngine 完成
 */
object EpubPaginator {

    private const val TAG = "EpubPaginator"


    suspend fun paginate(
        context: Context,
        chapter: Chapter,
        pageWidthPx: Int,
        pageHeightPx: Int,
        fontSizeSp: Float,
        lineSpacing: Float,
        horizontalPaddingDp: Dp = 18.dp,
        verticalPaddingDp: Dp = 8.dp,
        bottomExtraDp: Dp = 12.dp
    ): List<PageContent> =
        withContext(Dispatchers.Default) {


            val density =
                context.resources.displayMetrics.density

            val scaledDensity =
                context.resources.displayMetrics.scaledDensity


            val paddingHorizontal =
                (horizontalPaddingDp.value * density).toInt()


            val paddingTop =
                (verticalPaddingDp.value * density).toInt()


            val paddingBottom =
                paddingTop +
                (bottomExtraDp.value * density).toInt()



            val contentWidth =
                (pageWidthPx -
                        paddingHorizontal * 2)
                    .coerceAtLeast(1)


            val contentHeight =
                (pageHeightPx -
                        paddingTop -
                        paddingBottom)
                    .coerceAtLeast(1)



            if (chapter.blocks.isEmpty()) {

                return@withContext listOf(
                    PageContent(
                        chapter.title,
                        listOf(
                            Block.Text(
                                chapter.title
                            )
                        )
                    )
                )
            }



            /*
             * 图片尺寸缓存
             *
             * 防止分页过程中重复decode图片
             */
            val imageSizes =
                mutableMapOf<String, Pair<Int,Int>>()



            chapter.blocks.forEach { block ->

                if (block is Block.Image) {

                    if (!imageSizes.containsKey(block.path)) {

                        loadImageSize(block.path)
                            ?.let {
                                imageSizes[block.path] = it
                            }
                    }
                }
            }



            val engine =
                TextLayoutEngine(
                    width = contentWidth,
                    height = contentHeight,
                    fontSizePx =
                        fontSizeSp * scaledDensity,
                    lineSpacing = lineSpacing,
                    imageSizes = imageSizes
                )


            val pages =
                engine.paginate(
                    chapter
                )



            if (pages.isEmpty()) {

                Log.w(
                    TAG,
                    "empty pages:${chapter.title}"
                )


                return@withContext listOf(
                    PageContent(
                        chapter.title,
                        listOf(
                            Block.Text(
                                chapter.title
                            )
                        )
                    )
                )
            }



            pages
        }



    /**
     * 读取图片真实尺寸
     */
    private fun loadImageSize(
        path:String
    ):Pair<Int,Int>? {

        return try {

            val data =
                RustEpubEngine.loadImage(path)
                    ?: return null


            if(data.isEmpty())
                return null



            val options =
                BitmapFactory.Options()
                    .apply {

                        inJustDecodeBounds=true
                    }


            BitmapFactory.decodeByteArray(
                data,
                0,
                data.size,
                options
            )


            if(
                options.outWidth<=0 ||
                options.outHeight<=0
            ){

                null

            }else{

                Pair(
                    options.outWidth,
                    options.outHeight
                )
            }


        }catch(e:Exception){

            Log.w(
                TAG,
                "image size failed:$path",
                e
            )

            null
        }
    }
}