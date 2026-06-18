package io.casehub.drafthouse;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
@IfBuildProperty(name = "casehub.drafthouse.persistence.enabled", stringValue = "true")
public class JpaDebateSessionStore implements DebateSessionStore {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public void save(DebateSessionSnapshot snapshot) {
        DebateSessionEntity existing = em.find(DebateSessionEntity.class, snapshot.channelId());
        if (existing != null) {
            existing.debateSessionId = snapshot.debateSessionId();
            existing.channelName = snapshot.channelName();
            existing.documents.clear();
            existing.documents.addAll(snapshot.documents().stream()
                    .map(d -> new DebateSessionEntity.DocumentEmbeddable(d.path(), d.label()))
                    .toList());
            if (snapshot.comparison() != null) {
                existing.comparisonPathA = snapshot.comparison().pathA();
                existing.comparisonPathB = snapshot.comparison().pathB();
            } else {
                existing.comparisonPathA = null;
                existing.comparisonPathB = null;
            }
            existing.participants.clear();
            existing.participants.putAll(snapshot.participants());
            em.merge(existing);
        } else {
            em.persist(DebateSessionEntity.fromSnapshot(snapshot));
        }
    }

    @Override
    @Transactional
    public Optional<DebateSessionSnapshot> load(UUID channelId) {
        DebateSessionEntity entity = em.find(DebateSessionEntity.class, channelId);
        return Optional.ofNullable(entity).map(DebateSessionEntity::toSnapshot);
    }

    @Override
    @Transactional
    public void remove(UUID channelId) {
        DebateSessionEntity entity = em.find(DebateSessionEntity.class, channelId);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    @Transactional
    public Collection<DebateSessionSnapshot> loadAll() {
        return em.createQuery("SELECT e FROM DebateSessionEntity e", DebateSessionEntity.class)
                .getResultList()
                .stream()
                .map(DebateSessionEntity::toSnapshot)
                .toList();
    }
}
