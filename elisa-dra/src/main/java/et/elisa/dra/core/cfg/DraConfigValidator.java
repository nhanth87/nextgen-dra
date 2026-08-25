package et.elisa.dra.core.cfg;

import et.elisa.dra.core.engine.Action;
import et.elisa.dra.core.engine.Matcher;
import et.elisa.dra.core.engine.ThMode;
import et.elisa.dra.core.lb.LbStrategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DraConfigValidator {

    public List<String> validate(RuleSetFile file, int currentVersion) {
        List<String> errors = new ArrayList<>();
        if (file == null) {
            errors.add("config is null");
            return errors;
        }
        if (file.version() <= currentVersion) {
            errors.add("version must increase: candidate=" + file.version()
                    + " current=" + currentVersion);
        }
        validateSelf(file, errors);
        Set<String> groupNames = validateGroups(file, errors);
        validateRules(file, errors, groupNames);
        return errors;
    }

    private static void validateSelf(RuleSetFile file, List<String> errors) {
        RuleSetFile.Self self = file.self();
        if (self == null || self.originHost() == null || self.originHost().isBlank()) {
            errors.add("self.originHost is required");
        }
        if (self == null || self.realms() == null || self.realms().isEmpty()) {
            errors.add("self.realms must list at least one realm");
            return;
        }
        for (String r : self.realms()) {
            if (r == null || r.isBlank()) {
                errors.add("self.realms contains a blank entry");
                break;
            }
        }
    }

    private static Set<String> validateGroups(RuleSetFile file, List<String> errors) {
        Set<String> names = new HashSet<>();
        if (file.peerGroups() != null) {
            for (var e : file.peerGroups().entrySet()) {
                String name = e.getKey();
                RuleSetFile.GroupCfg g = e.getValue();
                if (!names.add(name)) {
                    errors.add("duplicate peerGroup name: " + name);
                }
                if (g.lb() == null) {
                    errors.add("group '" + name + "' missing lb strategy");
                } else {
                    try {
                        LbStrategy.valueOf(g.lb().toUpperCase(Locale.ROOT).replace('-', '_'));
                    } catch (IllegalArgumentException ex) {
                        errors.add("group '" + name + "' unknown lb strategy '" + g.lb()
                                + "' (supported RR WEIGHTED_RR LEAST_OUTSTANDING LOAD_AWARE)");
                    }
                }
                if (g.peers() == null || g.peers().isEmpty()) {
                    errors.add("group '" + name + "' needs at least one peer");
                } else {
                    Set<String> ids = new HashSet<>();
                    for (RuleSet.PeerWeight p : g.peers()) {
                        if (p.id() == null || p.id().isBlank()) {
                            errors.add("group '" + name + "' contains a peer with blank id");
                            continue;
                        }
                        if (!ids.add(p.id())) {
                            errors.add("group '" + name + "' duplicate peer id '" + p.id() + "'");
                        }
                        if (p.weight() < 0) {
                            errors.add("group '" + name + "' peer '" + p.id()
                                    + "' negative weight");
                        }
                    }
                }
                RuleSetFile.Failover f = g.failover();
                if (f != null && f.maxRetries() != null && f.maxRetries() < 0) {
                    errors.add("group '" + name + "' failover.maxRetries must be >= 0");
                }
            }
        }
        return names;
    }

    private static void validateRules(RuleSetFile file, List<String> errors, Set<String> groupNames) {
        if (file.rules() == null) {
            return;
        }
        Set<String> ruleNames = new HashSet<>();
        for (int i = 0; i < file.rules().size(); i++) {
            RuleSetFile.RuleCfg r = file.rules().get(i);
            String label = r.name() == null ? ("rules[" + i + "]") : "'" + r.name() + "'";
            if (r.name() == null || r.name().isBlank()) {
                errors.add(label + " missing name");
            } else if (!ruleNames.add(r.name())) {
                errors.add("duplicate rule name '" + r.name() + "'");
            }
            if (r.when() == null) {
                errors.add("rule " + label + " missing 'when' matcher");
            } else {
                checkMatcherTree(label, r.when().matcher(), errors);
            }
            if (r.then() == null) {
                errors.add("rule " + label + " missing 'then' action");
            } else {
                checkAction(label, r.then().action(), errors, groupNames, file, r);
            }
        }
    }

    private static void checkMatcherTree(String label, Matcher m, List<String> errors) {
        if (m == null) {
            return;
        }
        if (m instanceof Matcher.And a) {
            a.parts().forEach(p -> checkMatcherTree(label, p, errors));
        } else if (m instanceof Matcher.Or o) {
            o.parts().forEach(p -> checkMatcherTree(label, p, errors));
        } else if (m instanceof Matcher.Not n) {
            checkMatcherTree(label, n.inner(), errors);
        } else if (m instanceof Matcher.HasApp h && h.appId() <= 0) {
            errors.add("rule " + label + " invalid application id " + h.appId()
                    + " (must be numeric > 0)");
        } else if (m instanceof Matcher.HasCmd c) {
            for (int code : c.codes()) {
                if (code < 0 || code > 0xFFFFFF) {
                    errors.add("rule " + label + " command code out of range: " + code);
                }
            }
        }
    }

    private static void checkAction(String label, Action a, List<String> errors,
                                    Set<String> groupNames, RuleSetFile file,
                                    RuleSetFile.RuleCfg rule) {
        if (a instanceof Action.Forward f) {
            if (!groupNames.contains(f.group())) {
                errors.add("rule " + label + " forwards to undefined group '" + f.group()
                        + "' (orphan reference)");
            }
            if (f.sticky() != null
                    && !Matcher.PathNames.isKnownKey(f.sticky().key())) {
                errors.add("rule " + label + " sticky key '" + f.sticky().key()
                        + "' is not an extractable key");
            }
            if (f.th() == null || f.th() == ThMode.OFF) {
                if (addressesSelfRealm(rule, file)) {
                    errors.add("rule " + label + " forwards messages addressed to self realm"
                            + " with TH=OFF (configuration loop)");
                }
            }
        } else if (a instanceof Action.Redirect rd) {
            if (rd.host() == null || rd.host().isBlank()) {
                errors.add("rule " + label + " redirect requires host");
            }
            if (rd.cacheSeconds() < 0) {
                errors.add("rule " + label + " redirect cacheSecs must be >= 0");
            }
        } else if (a instanceof Action.Reject j) {
            if (j.resultCode() <= 0) {
                errors.add("rule " + label + " reject resultCode must be positive");
            }
        }
    }

    private static boolean addressesSelfRealm(RuleSetFile.RuleCfg rule, RuleSetFile file) {
        if (file.self() == null || file.self().realms() == null || rule.when() == null) {
            return false;
        }
        List<Matcher> leaves = new ArrayList<>();
        collectLeaves(rule.when().matcher(), leaves);
        for (Matcher m : leaves) {
            String value = null;
            String op = null;
            boolean relevant = false;
            if (m instanceof Matcher.RealmMatch rm && rm.field() == Matcher.RealmMatch.Field.DEST) {
                value = rm.value();
                op = rm.op().name();
                relevant = true;
            } else if (m instanceof Matcher.AvpMatch am
                    && "DEST_REALM".equals(am.path())
                    && am.op() != Matcher.AvpMatch.Op.IN_LIST
                    && am.op() != Matcher.AvpMatch.Op.IP_IN_CIDR) {
                value = am.value();
                op = am.op().name();
                relevant = true;
            }
            if (relevant && value != null) {
                for (String realm : file.self().realms()) {
                    if (overlaps(value, op, realm)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void collectLeaves(Matcher m, List<Matcher> out) {
        if (m == null) {
            return;
        }
        if (m instanceof Matcher.And a) {
            a.parts().forEach(p -> collectLeaves(p, out));
        } else if (m instanceof Matcher.Or o) {
            o.parts().forEach(p -> collectLeaves(p, out));
        } else if (m instanceof Matcher.Not n) {
            collectLeaves(n.inner(), out);
        } else {
            out.add(m);
        }
    }

    private static boolean overlaps(String matcherValue, String op, String selfRealm) {
        String v = matcherValue.toLowerCase(Locale.ROOT);
        String s = selfRealm.toLowerCase(Locale.ROOT);
        if ("REGEX".equals(op)) {
            try {
                return java.util.regex.Pattern.compile(matcherValue,
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(selfRealm).matches();
            } catch (RuntimeException e) {
                return false;
            }
        }
        if (v.length() < 4 || s.length() < 4) {
            return v.equals(s);
        }
        return s.equals(v) || s.endsWith(v) || v.endsWith(s);
    }
}
