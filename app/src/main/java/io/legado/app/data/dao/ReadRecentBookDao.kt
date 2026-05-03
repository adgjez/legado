package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecentBook

@Dao
interface ReadRecentBookDao {

    @Query(
        """
        select books.* from readRecentBooks
        inner join books on readRecentBooks.bookUrl = books.bookUrl
        where books.name != ''
        order by readRecentBooks.lastRead desc
        limit :limit
        """
    )
    fun recentBooks(limit: Int): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: ReadRecentBook)

    @Query("delete from readRecentBooks")
    fun clear()

    @Query("delete from readRecentBooks where bookUrl = :bookUrl")
    fun delete(bookUrl: String)
}
