package testsupport.auditing;

import com.linguaflow.common.persistence.AuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Test-only entity. Lives outside com.linguaflow on purpose: the app's entity scan
 * would otherwise pick it up and fail schema validation in other tests.
 */
@Entity
@Table(name = "audited_test_entity")
public class AuditedTestEntity extends AuditableEntity {

	@Id
	@GeneratedValue
	private Long id;

	private String name;

	protected AuditedTestEntity() {
	}

	public AuditedTestEntity(String name) {
		this.name = name;
	}

	public void rename(String name) {
		this.name = name;
	}

}
