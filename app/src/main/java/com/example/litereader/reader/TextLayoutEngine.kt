package com.example.litereader.reader

import android.graphics.BitmapFactory
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.example.litereader.domain.model.Block


/**
 * 真正的排版分页引擎
 *
 * 不负责读取 EPUB
 *
 * 只负责:
 *
 * Block
 *   |
 *   v
 * PageContent
 *
 */
class TextLayoutEngine(

    private val width:Int,

    private val height:Int,

    private val fontSizePx:Float,

    private val lineSpacing:Float,

    private val imageSizes:
        Map<String,Pair<Int,Int>>

) {


    companion object {

        private const val TAG =
            "TextLayoutEngine"
    }



    private val textPaint =
        TextPaint()
            .apply {

                isAntiAlias=true

                textSize =
                    fontSizePx
            }



    private val titlePaint =
        TextPaint()
            .apply {

                isAntiAlias=true

                textSize =
                    fontSizePx + 6f
            }



    /**
     * 外部入口
     */
    fun paginate(
        chapter: com.example.litereader.domain.model.Chapter
    ):List<PageContent>{


        val pages =
            mutableListOf<PageContent>()



        var current =
            mutableListOf<Block>()


        var usedHeight = 0



        /*
         * 第一页加入章节标题
         */
        val title =
            Block.Title(
                chapter.title
            )


        val titleHeight =
            measureBlock(title)



        current.add(title)

        usedHeight += titleHeight



        for(block in chapter.blocks){


            val result =
                layoutBlock(
                    block,
                    height-usedHeight
                )



            for(piece in result){


                val pieceHeight =
                    measureBlock(piece)



                /*
                 * 当前页放不下
                 */
                if(
                    usedHeight+
                    pieceHeight
                    >
                    height
                    &&
                    current.isNotEmpty()
                ){


                    pages.add(
                        PageContent(
                            chapter.title,
                            current.toList()
                        )
                    )


                    current.clear()

                    usedHeight=0
                }



                current.add(piece)

                usedHeight+=pieceHeight

            }

        }



        if(current.isNotEmpty()){

            pages.add(
                PageContent(
                    chapter.title,
                    current.toList()
                )
            )
        }



        return pages
    }





    /**
     * 单个Block拆分页
     */
    private fun layoutBlock(
        block:Block,
        availableHeight:Int
    ):List<Block>{


        return when(block){


            is Block.Text -> {

                splitText(
                    block,
                    textPaint,
                    availableHeight
                )

            }


            is Block.Title -> {

                splitText(
                    Block.Text(block.text),
                    titlePaint,
                    availableHeight
                )
                .map {

                    Block.Title(
                        (it as Block.Text)
                            .text
                    )
                }

            }


            is Block.Image -> {


                listOf(block)

            }
        }

    }
    /**
     * 使用 StaticLayout 精确切文本
     *
     * 不使用 breakText
     *
     * StaticLayout 与 Android TextView 相同排版
     */
    private fun splitText(
        block: Block.Text,
        paint: TextPaint,
        availableHeight: Int
    ): List<Block> {


        val text = block.text


        if(text.isEmpty()) {
            return emptyList()
        }


        val layout =
            StaticLayout.Builder
                .obtain(
                    text,
                    0,
                    text.length,
                    paint,
                    width
                )
                .setIncludePad(false)
                .setLineSpacing(
                    0f,
                    lineSpacing
                )
                .build()



        /*
         * 当前空间最多显示多少行
         */
        var endLine = 0

        var used = 0


        while(endLine < layout.lineCount){


            val lineHeight =
                layout.getLineBottom(endLine) -
                layout.getLineTop(endLine)


            if(
                used + lineHeight
                >
                availableHeight
            ){

                break
            }


            used += lineHeight

            endLine++
        }



        /*
         * 至少保证一行
         */
        if(endLine<=0){

            endLine=1
        }



        val result =
            mutableListOf<Block>()



        var start = 0



        while(start < text.length){


            val subLayout =
                StaticLayout.Builder
                    .obtain(
                        text,
                        start,
                        text.length,
                        paint,
                        width
                    )
                    .setIncludePad(false)
                    .setLineSpacing(
                        0f,
                        lineSpacing
                    )
                    .build()



            val lines =
                minOf(
                    endLine,
                    subLayout.lineCount
                )



            val end =
                subLayout
                    .getLineEnd(lines-1)



            if(end<=0 ||
                end<=start){

                break
            }



            val piece =
                text.substring(
                    start,
                    end
                )



            result.add(
                Block.Text(
                    piece
                )
            )



            start = end



            /*
             * 跳过无意义的空换行
             * 但不丢正文
             */
            while(
                start < text.length &&
                text[start]=='\n' &&
                piece.endsWith("\n")
            ){

                start++
            }

        }



        return result
    }





    /**
     * 计算Block真实高度
     */
    private fun measureBlock(
        block: Block
    ):Int {


        return when(block){


            is Block.Text -> {

                measureText(
                    block.text,
                    textPaint
                )
            }



            is Block.Title -> {

                measureText(
                    block.text,
                    titlePaint
                )
            }



            is Block.Image -> {


                measureImage(
                    block
                )

            }

        }

    }





    private fun measureText(
        text:String,
        paint:TextPaint
    ):Int{


        if(text.isEmpty())
            return 0



        val layout =
            StaticLayout.Builder
                .obtain(
                    text,
                    0,
                    text.length,
                    paint,
                    width
                )
                .setIncludePad(false)
                .setLineSpacing(
                    0f,
                    lineSpacing
                )
                .build()



        return layout.height + 8

    }





    /**
     * 图片高度计算
     */
    private fun measureImage(
        image:Block.Image
    ):Int {


        val size =
            imageSizes[image.path]
                ?: return width



        val scale = if (size.first > width) {
            width.toFloat() / size.first.toFloat()
        } else {
            1f
        }



        return (
            size.second *
            scale
        ).toInt()
        +
        8
    }


}	