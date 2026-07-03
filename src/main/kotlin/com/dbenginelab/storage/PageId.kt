package com.dbenginelab.storage

data class PageId(val fileId: Int, val pageNumber: Int) {
    companion object {
        const val INVALID_PAGE_NUMBER: Int = -1
    }
}