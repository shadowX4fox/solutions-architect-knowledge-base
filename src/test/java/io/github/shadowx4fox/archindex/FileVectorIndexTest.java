package io.github.shadowx4fox.archindex;

import io.github.shadowx4fox.archindex.index.FileVectorIndex;
import io.github.shadowx4fox.archindex.index.VectorIndex;

class FileVectorIndexTest extends VectorIndexContract {

	@Override
	VectorIndex open() {
		return new FileVectorIndex(tmp.resolve("index/vectors-1024.bin"), 1024);
	}

}
