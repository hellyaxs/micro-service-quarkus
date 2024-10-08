package infra.repositories;

import domain.Candidate;
import domain.Election;
import domain.ElectionRepository;
import domain.annotations.Principal;
import infra.repositories.entities.ElectionCandidateEntity;
import infra.repositories.entities.ElectionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;

@Principal
@ApplicationScoped
public class SQLElectionRepository  implements ElectionRepository {

    private final EntityManager entityManager;

    public SQLElectionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void submit(Election election) {
        ElectionEntity electionEntity = ElectionEntity.fromDomain(election);
        entityManager.merge(electionEntity);

        election.votes().entrySet().stream().map(entry -> ElectionCandidateEntity.fromDomain(election, entry.getKey(), entry.getValue()))
                .forEach(entityManager::merge);
    }

    @Override
    public List<Election> findAll() {
        Stream<Object[]> stream = entityManager.createNativeQuery("SELECT e.id AS election_id, c.id AS candidate_id, c.photo, c.given_name, c.family_name, c.email, c.phone, c.job_title, ec.votes FROM elections AS e INNER JOIN election_candidate AS ec ON ec.election_id = e.id INNER JOIN candidates AS c ON ec.candidate_id = c.id")
                .getResultStream();

        Map<String, List<Object[]>> map = stream.collect(groupingBy(o -> (String) o[0]));

        return map.entrySet()
                .stream()
                .map(entry -> {
                    Map.Entry<Candidate, Integer>[] candidates = entry.getValue()
                            .stream()
                            .map(row -> Map.entry(new Candidate((String) row[1],
                                            Optional.ofNullable((String) row[2]),
                                            (String) row[3],
                                            (String) row[4],
                                            Optional.ofNullable((String) row[5]),
                                            String.valueOf(Optional.ofNullable((String) row[6])),
                                            Optional.ofNullable((String) row[7])),
                                    (Integer) row[8]))
                            .toArray(Map.Entry[]::new);

                    return new Election(entry.getKey(), Map.ofEntries(candidates));
                })
                .toList();
    }

    @Transactional
    public void sync(Election election) {
        election.votes()
                .entrySet()
                .stream()
                .map(entry -> ElectionCandidateEntity.fromDomain(election, entry.getKey(), entry.getValue()))
                .forEach(entityManager::merge);
    }
}
