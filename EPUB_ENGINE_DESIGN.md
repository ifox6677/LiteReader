# Kotlin + Rust EPUB 阅读引擎设计方案

## 目标

使用 Rust 作为 EPUB 底层解析引擎，Android Kotlin + Compose 负责阅读显示。

目标：

- 快速打开大型 EPUB
- 支持 EPUB 图片
- 支持左右分页
- 支持上下滚动
- 支持字体调整
- 支持低内存运行
- 支持 LibGen 大型 EPUB


---

# 总体架构
Android Kotlin

Compose Reader
|
ViewModel
|
Repository
|
Rust Bridge
|
Rust EPUB Engine
  |

epub crate
|
EPUB(zip)
|
XHTML / Image / Metadata



职责：

## Rust

负责：

- EPUB解析
- ZIP读取
- 章节索引
- XHTML读取
- 图片资源读取
- 文本提取
- 缓存


## Kotlin

负责：

- 页面显示
- 左右翻页
- 上下滚动
- 字体设置
- 主题
- 阅读位置


---

# Rust模块结构



rust-reader

src/

├── lib.rs

├── epub_engine.rs

├── chapter.rs

├── resource.rs

├── image.rs

└── cache.rs



---

# Rust依赖


```toml
[dependencies]

epub = "2"

serde = "1"

serde_json = "1"

EPUB处理流程
1. 打开书籍

输入：

book.epub

流程：

openBook()

    |

epub::EpubDoc::new()

    |

读取container.xml

    |

读取content.opf

    |

建立章节列表

    |

返回BookInfo


注意：

打开阶段不解析正文。

不加载图片。

Book数据结构

Rust:

struct BookInfo {

    title:String,

    author:String,

    cover:String,

    chapters:Vec<ChapterInfo>

}


struct ChapterInfo {

    index:u32,

    title:String,

    path:String

}

章节加载流程

用户打开章节：

loadChapter(index)

        |

epub crate读取xhtml

        |

解析HTML

        |

转换Block

        |

返回Android


返回：

enum Block {

    Text(String),

    Image(String),

    Title(String)

}


示例：

[
 Text("hello"),

 Image("images/a.jpg"),

 Text("world")
]

图片处理流程

原则：

不提前加载图片。

流程：

HTML

 |

<img src="a.jpg">

 |

保存图片路径

 |

Compose需要

 |

调用loadImage()

 |

Rust读取epub资源

 |

返回byte[]

 |

Android Bitmap显示


接口：

loadImage(path:String)->Vec<u8>
分页设计

分页不在Rust实现。

原因：

分页依赖：

屏幕大小
字体大小
行距
用户设置

流程：

Rust

Chapter

 |

Block列表

 |

Kotlin Compose

 |

Layout测量

 |

生成Page

左右分页

Android：

HorizontalPager

      |

Page

      |

Block

      |

Text/Image


流程：

上一页

Page index -1


下一页

Page index +1

上下滚动

Android：

LazyColumn

 |

Text

 |

Image

 |

Text


Rust无需改变。

缓存设计
Chapter Cache

缓存：

当前章节
下一章节

结构：

chapterId

↓

Block列表

Image Cache

LRU缓存：

imagePath

↓

Bitmap

Rust-Kotlin接口

使用 UniFFI 或 JNI。

核心接口：

打开书
openBook(path:String)
->BookInfo

获取目录
getChapters()

->Vec<ChapterInfo>

加载章节
loadChapter(index:u32)

->Vec<Block>

加载图片
loadImage(path:String)

->Vec<u8>

关闭
closeBook()

Kotlin调用流程

打开：

val book =
RustEngine.openBook(path)


显示目录：

val chapters =
RustEngine.getChapters()


阅读：

val blocks =
RustEngine.loadChapter(index)


图片：

val data =
RustEngine.loadImage(path)

性能优化原则
必须：
Lazy Loading
章节按需读取
图片按需读取
LRU缓存
后台预加载下一章节
禁止：
启动时解析全部章节
启动时加载全部图片
保存整本书HTML
使用WebView作为阅读核心
最终流程
EPUB文件

 |

Rust epub crate

 |

BookInfo

 |

ChapterIndex

 |

loadChapter()

 |

Block数据

 |

Compose

 |

分页/滚动

 |

loadImage()

 |

显示图片

开发顺序
Phase 1

Rust接入：

epub crate
打开EPUB
获取目录
Phase 2

实现：

loadChapter
图片读取
Phase 3

Android改造：

Compose分页
图片显示
阅读记录
Phase 4

优化：

缓存
预加载
大文件测试

