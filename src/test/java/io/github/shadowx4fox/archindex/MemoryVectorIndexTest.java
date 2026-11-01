package io.github.shadowx4fox.archindex;

import io.github.shadowx4fox.archindex.index.MemoryVectorIndex;
import io.github.shadowx4fox.archindex.index.VectorIndex;

class MemoryVectorIndexTest extends VectorIndexContract {

	@Override
	VectorIndex open() {
		return new MemoryVectorIndex();
	}

}
