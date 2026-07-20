//! JNI 层：为 Kotlin 暴露 EPUB 与 PDF 引擎接口。

mod cache;
mod chapter;
mod epub_engine;
mod image;
mod pdf_engine;
mod resource;
mod txt_engine;

use std::sync::Mutex;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jstring};
use jni::JNIEnv;

use epub_engine::EpubEngine;
use pdf_engine::PdfEngine;
use txt_engine::TxtEngine;

/// 全局 EPUB 引擎实例。
static ENGINE: Mutex<Option<EpubEngine>> = Mutex::new(None);

/// 全局 PDF 引擎实例。
static PDF_ENGINE: Mutex<Option<PdfEngine>> = Mutex::new(None);

/// 全局 TXT 引擎实例。
static TXT_ENGINE: Mutex<Option<TxtEngine>> = Mutex::new(None);

fn jstring_from(env: &mut JNIEnv, s: &str) -> jstring {
    let obj = env.new_string(s).expect("failed to create jstring");
    obj.into_raw()
}

fn jbytes_from(env: &mut JNIEnv, data: &[u8]) -> jbyteArray {
    let bytes: JByteArray = env
        .new_byte_array(data.len() as i32)
        .expect("failed to create jbyteArray");
    env.set_byte_array_region(&bytes, 0, unsafe {
        std::slice::from_raw_parts(data.as_ptr() as *const i8, data.len())
    })
    .expect("failed to set byte array region");
    bytes.into_raw()
}

fn error_json(msg: &str) -> String {
    serde_json::json!({ "error": msg }).to_string()
}

// ============================================================================
// JNI 函数：命名必须与 Kotlin 包名/类名/方法名一一对应
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustEpubEngine_openBook(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return jstring_from(&mut env, &error_json("invalid path")),
    };

    match EpubEngine::open(&path) {
        Ok(engine) => {
            let info = engine.book_info().clone();
            {
                let mut guard = ENGINE.lock().unwrap();
                *guard = Some(engine);
            }
            match serde_json::to_string(&info) {
                Ok(json) => jstring_from(&mut env, &json),
                Err(e) => jstring_from(&mut env, &error_json(&e.to_string())),
            }
        }
        Err(e) => jstring_from(&mut env, &error_json(&e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustEpubEngine_getChapters(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let guard = ENGINE.lock().unwrap();
    match &*guard {
        Some(engine) => {
            let json = serde_json::to_string(engine.chapters()).unwrap_or_default();
            jstring_from(&mut env, &json)
        }
        None => jstring_from(&mut env, &error_json("book not opened")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustEpubEngine_loadChapter(
    mut env: JNIEnv,
    _class: JClass,
    index: jint,
) -> jstring {
    let mut guard = ENGINE.lock().unwrap();
    match guard.as_mut() {
        Some(engine) => match engine.load_chapter(index as u32) {
            Ok(blocks) => match serde_json::to_string(&blocks) {
                Ok(json) => jstring_from(&mut env, &json),
                Err(e) => jstring_from(&mut env, &error_json(&e.to_string())),
            },
            Err(e) => jstring_from(&mut env, &error_json(&e)),
        },
        None => jstring_from(&mut env, &error_json("book not opened")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustEpubEngine_loadImage(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jbyteArray {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let mut guard = ENGINE.lock().unwrap();
    match guard.as_mut() {
        Some(engine) => match engine.load_image(&path) {
            Ok(data) => jbytes_from(&mut env, &data),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustEpubEngine_getCover(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jbyteArray {
    // 为封面提取单独打开一次书籍，避免污染当前阅读状态
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match EpubEngine::open(&path) {
        Ok(mut engine) => match engine.get_cover() {
            Some(data) => jbytes_from(&mut env, &data),
            None => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustEpubEngine_closeBook(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = ENGINE.lock().unwrap();
    if let Some(engine) = guard.as_mut() {
        engine.close();
    }
    *guard = None;
}

// ============================================================================
// PDF JNI 函数
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustPdfEngine_openPdf(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return jstring_from(&mut env, &error_json("invalid path")),
    };

    match PdfEngine::open(&path) {
        Ok(engine) => {
            let page_count = engine.page_count();
            {
                let mut guard = PDF_ENGINE.lock().unwrap();
                *guard = Some(engine);
            }

            // 构建 BookInfo JSON：每页一个"章节"
            let title = std::path::Path::new(&path)
                .file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| "未知书名".to_string());

            let chapters: Vec<chapter::ChapterInfo> = (0..page_count)
                .map(|i| chapter::ChapterInfo {
                    index: i,
                    title: format!("第 {} 页", i + 1),
                    path: format!("pdf://page/{}", i),
                })
                .collect();

            let info = chapter::BookInfo {
                title,
                author: "未知作者".to_string(),
                cover: String::new(),
                chapters,
            };

            match serde_json::to_string(&info) {
                Ok(json) => jstring_from(&mut env, &json),
                Err(e) => jstring_from(&mut env, &error_json(&e.to_string())),
            }
        }
        Err(e) => jstring_from(&mut env, &error_json(&e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustPdfEngine_loadPdfChapter(
    mut env: JNIEnv,
    _class: JClass,
    index: jint,
) -> jstring {
    let mut guard = PDF_ENGINE.lock().unwrap();
    match guard.as_mut() {
        Some(_engine) => {
            let page_path = format!("pdf://page/{}", index);
            let blocks = vec![chapter::Block::Image(page_path)];
            match serde_json::to_string(&blocks) {
                Ok(json) => jstring_from(&mut env, &json),
                Err(e) => jstring_from(&mut env, &error_json(&e.to_string())),
            }
        }
        None => jstring_from(&mut env, &error_json("pdf not opened")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustPdfEngine_loadPdfImage(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jbyteArray {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    // 解析 pdf://page/{index}
    let page_index: u32 = match path.strip_prefix("pdf://page/") {
        Some(s) => match s.parse() {
            Ok(n) => n,
            Err(_) => return std::ptr::null_mut(),
        },
        None => return std::ptr::null_mut(),
    };

    let mut guard = PDF_ENGINE.lock().unwrap();
    match guard.as_mut() {
        Some(engine) => match engine.render_page(page_index) {
            Ok(data) => jbytes_from(&mut env, &data),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustPdfEngine_getPdfCover(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jbyteArray {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match PdfEngine::render_cover(&path) {
        Ok(data) => jbytes_from(&mut env, &data),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustPdfEngine_closePdf(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = PDF_ENGINE.lock().unwrap();
    if let Some(engine) = guard.as_mut() {
        engine.close();
    }
    *guard = None;
}

// ============================================================================
// TXT JNI 函数
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustTxtEngine_openTxt(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return jstring_from(&mut env, &error_json("invalid path")),
    };

    match TxtEngine::open(&path) {
        Ok(engine) => {
            let info = engine.book_info().clone();
            {
                let mut guard = TXT_ENGINE.lock().unwrap();
                *guard = Some(engine);
            }
            match serde_json::to_string(&info) {
                Ok(json) => jstring_from(&mut env, &json),
                Err(e) => jstring_from(&mut env, &error_json(&e.to_string())),
            }
        }
        Err(e) => jstring_from(&mut env, &error_json(&e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustTxtEngine_loadTxtChapter(
    mut env: JNIEnv,
    _class: JClass,
    index: jint,
) -> jstring {
    let mut guard = TXT_ENGINE.lock().unwrap();
    match guard.as_mut() {
        Some(engine) => match engine.load_chapter(index as u32) {
            Ok(blocks) => match serde_json::to_string(&blocks) {
                Ok(json) => jstring_from(&mut env, &json),
                Err(e) => jstring_from(&mut env, &error_json(&e.to_string())),
            },
            Err(e) => jstring_from(&mut env, &error_json(&e)),
        },
        None => jstring_from(&mut env, &error_json("txt not opened")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_litereader_reader_RustTxtEngine_closeTxt(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = TXT_ENGINE.lock().unwrap();
    if let Some(engine) = guard.as_mut() {
        engine.close();
    }
    *guard = None;
}
