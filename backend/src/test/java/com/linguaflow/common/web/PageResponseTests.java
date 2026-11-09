package com.linguaflow.common.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTests {

	@Test
	void copiesMetadataAndMapsContent() {
		var page = new PageImpl<>(List.of(1, 2), PageRequest.of(1, 2, Sort.by("lemma")), 5);

		PageResponse<String> response = PageResponse.from(page, n -> "#" + n);

		assertThat(response.content()).containsExactly("#1", "#2");
		assertThat(response.page()).isEqualTo(1);
		assertThat(response.size()).isEqualTo(2);
		assertThat(response.totalElements()).isEqualTo(5);
		assertThat(response.totalPages()).isEqualTo(3);
		assertThat(response.sort()).containsExactly(new PageResponse.SortOrder("lemma", "ASC"));
	}

}
