package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomCourseInfo
import com.gfs.iyango.core.model.ClassroomMetadata
import com.gfs.iyango.core.model.ClassroomPeriodGroup
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.core.model.ClassroomRoom

/** 教室查询网络层的统一分诊状态，语义与成绩客户端一致。 */
internal enum class YguClassroomClientStatus {
    OK,
    EMPTY,
    LOGIN,
    RESTRICTED,
    UNSUPPORTED,
    INVALID,
    ERROR,
}

/** 一次元数据页读取的产物；[cookieHeader] 是吸收 Set-Cookie 后的会话头。 */
internal data class YguClassroomMetadataLoad(
    val status: YguClassroomClientStatus,
    val message: String = "",
    val metadata: ClassroomMetadata = ClassroomMetadata(),
    val cookieHeader: String = "",
)

/** 一次教学楼字典读取的产物。 */
internal data class YguClassroomBuildingsLoad(
    val status: YguClassroomClientStatus,
    val message: String = "",
    val buildings: List<ClassroomBuilding> = emptyList(),
    val cookieHeader: String = "",
)

/** 一次占用查询的产物；OK/EMPTY 时 [result] 携带节次组与教室行。 */
internal data class YguClassroomQueryLoad(
    val status: YguClassroomClientStatus,
    val message: String = "",
    val result: ClassroomResult? = null,
    val cookieHeader: String = "",
)

/** 一次占用详情读取的产物。 */
internal data class YguClassroomDetailLoad(
    val status: YguClassroomClientStatus,
    val message: String = "",
    val courses: List<ClassroomCourseInfo> = emptyList(),
    val cookieHeader: String = "",
)

internal enum class YguClassroomParseStatus { OK, EMPTY, LOGIN, INVALID }

/** jsjy_query2 响应解析结论：节次组与教室行只在 OK/EMPTY 时可信。 */
internal data class YguClassroomParseResult(
    val status: YguClassroomParseStatus,
    val message: String = "",
    val periodGroups: List<ClassroomPeriodGroup> = emptyList(),
    val rooms: List<ClassroomRoom> = emptyList(),
)

internal enum class YguClassroomDetailStatus { OK, EMPTY, UNSUPPORTED, INVALID }

/** jsjy_jszyqk 响应解析结论。 */
internal data class YguClassroomDetailResult(
    val status: YguClassroomDetailStatus,
    val message: String = "",
    val courses: List<ClassroomCourseInfo> = emptyList(),
)
