package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_snippets")
data class CommandSnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val command: String,
    val category: String,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
