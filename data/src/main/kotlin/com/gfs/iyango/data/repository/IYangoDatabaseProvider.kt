package com.gfs.iyango.data.repository

import android.content.Context
import androidx.room.Room
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.IYangoDatabaseMigrations
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据库构造封装（懒创建进程级单例）。
 *
 * library 模块没有可判 debug/release 的 BuildConfig，因此 `allowDestructive` 改为
 * **构造参数**由 app 在绑定时传入：debug 传 true（无迁移可走时直接重建，便于开发），
 * release 必须传 false（防止用户数据被清库）。
 *
 * app 侧绑定方式（P2 集成）：为 [Boolean] 提供一个绑定（或直接 @Provides 本类），
 * 再 `@Provides fun db(p: IYangoDatabaseProvider): IYangoDatabase = p.get()`。
 *
 * @param context 应用级 Context（@ApplicationContext 注入）。
 * @param allowDestructive 是否允许破坏性迁移重建数据库；仅 debug 允许为 true。
 */
@Singleton
class IYangoDatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val allowDestructive: Boolean,
) {

    /** 缓存的数据库实例；[get] 双重检查锁创建。 */
    @Volatile
    private var cached: IYangoDatabase? = null

    /** 返回数据库单例；首次调用时按构造参数构建。 */
    fun get(): IYangoDatabase =
        cached ?: synchronized(this) {
            cached ?: build().also { cached = it }
        }

    /** 构建数据库；注册手写迁移，allowDestructive 为 true 时额外保留破坏性回退。 */
    private fun build(): IYangoDatabase {
        val builder = Room.databaseBuilder(context, IYangoDatabase::class.java, IYangoDatabase.NAME)
            // 手写迁移（v1→v2 调课唯一约束等）；无迁移路径可走且 allowDestructive 时才清库重建。
            .addMigrations(*IYangoDatabaseMigrations.ALL)
        if (allowDestructive) {
            // dropAllTables=true：版本升级无迁移时清库重建（仅 debug）。
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }
        return builder.build()
    }
}
