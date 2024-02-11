package ir.saltech.freedom.dto.user

import com.google.gson.annotations.SerializedName

data class VspList(
	@SerializedName("vspList") val vspList: MutableList<String> = mutableListOf()
)