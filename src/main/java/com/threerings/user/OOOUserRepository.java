//
// $Id$

package com.threerings.user;

import static com.threerings.user.Log.log;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import com.samskivert.io.PersistenceException;
import com.samskivert.jdbc.ConnectionProvider;
import com.samskivert.jdbc.DatabaseLiaison;
import com.samskivert.jdbc.JDBCUtil;
import com.samskivert.jdbc.jora.FieldMask;
import com.samskivert.jdbc.jora.Table;
import com.samskivert.servlet.user.Password;
import com.samskivert.servlet.user.User;
import com.samskivert.servlet.user.UserExistsException;
import com.samskivert.servlet.user.UserRepository;
import com.samskivert.servlet.user.Username;
import com.samskivert.util.ArrayIntSet;
import com.samskivert.util.ArrayUtil;
import com.samskivert.util.Calendars;
import com.samskivert.util.HashIntMap;
import com.samskivert.util.IntIntMap;
import com.samskivert.util.StringUtil;
import com.samskivert.util.Tuple;

/**
 * Extends the samskivert user repository with custom Three Rings business.
 */
public class OOOUserRepository extends UserRepository
{
    /** Access granted, user is not banned nor coming from a tainted machine. */
    public static final int ACCESS_GRANTED = 0;

    /** User is trying to create a new account from a tainted machine. */
    public static final int NEW_ACCOUNT_TAINTED = 1;

    /** The user is banned from playing. */
    public static final int ACCOUNT_BANNED = 2;

    /** The user can not create another free account on this machine. */
    public static final int NO_NEW_FREE_ACCOUNT = 3;

    /** The user has bounced a check or reversed a payment. */
    public static final int DEADBEAT = 4;

    /**
     * Creates the repository and opens the user database. The database identifier used to fetch
     * our database connection is documented by {@link #USER_REPOSITORY_IDENT}.
     *
     * @param provider the database connection provider.
     */
    public OOOUserRepository (ConnectionProvider provider)
        throws PersistenceException
    {
        super(provider);

        // purge validation records that have expired
        purgeValidationRecords();
    }

    /**
     * Creates a new user record in the repository with no auxiliary data.
     */
    public int createUser (
        Username username, Password password, String email, int siteId, int tagId)
        throws UserExistsException, PersistenceException
    {
        return createUser(username, password, email, siteId, tagId, null, (byte)-1, null);
    }

    /**
     * Creates a new user record in the repository.
     */
    public int createUser (Username username, Password password, String email, int siteId,
                           int tagId, int birthyear, byte gender, String missive)
        throws UserExistsException, PersistenceException
    {
        // convert birth year to a fake birthday (Jan 1 of that year)
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, birthyear);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.MONTH, 0);
        return createUser(username, password, email, siteId, tagId,
                          new Date(cal.getTimeInMillis()), gender, missive);
    }

    /**
     * Creates a new user record in the repository.
     */
    public int createUser (Username username, Password password, String email, int siteId,
                           int tagId, Date birthday, byte gender, String missive)
        throws UserExistsException, PersistenceException
    {
        OOOUser user = new OOOUser();
        user.setDirtyMask(_utable.getFieldMask());
        populateUser(user, username, password, email, siteId, tagId);
        int userId = insertUser(user);

        // store their auxiliary data (if provided)
        if (birthday != null || gender >= 0 || missive != null) {
            OOOAuxData auser = new OOOAuxData();
            auser.userId = userId;
            auser.birthday = birthday;
            auser.gender = gender;
            auser.missive = (missive == null ? "" : missive);
            // if we allow this to be longer it won't fit in the database column and the insert
            // will choke
            auser.missive = StringUtil.truncate(auser.missive, 255);
            try {
                insert(_atable, auser);
            } catch (PersistenceException pe) {
                Throwable err = (pe.getCause() == null) ? pe : pe.getCause();
                log.warning("Failed to insert auxdata " + auser + ": " + err + ".");
            }
        }

        // put this user down on the permanent record
        HistoricalUser huser = new HistoricalUser();
        huser.userId = userId;
        huser.username = username.getUsername();
        huser.created = user.created;
        huser.siteId = siteId;
        insert(_htable, huser);

        return userId;
    }

    /**
     * Looks up a user by username and optionally loads their machine identifier information.
     *
     * @return the user with the specified user id or null if no user with that id exists.
     */
    public OOOUser loadUser (String username, boolean loadIdents)
        throws PersistenceException
    {
        OOOUser user = (OOOUser)loadUser(username);
        if (user == null || !loadIdents) {
            return user;
        }
        loadMachineIdents(user);
        return user;
    }

    /**
     * Looks up a user by email address and optionally loads their machine identifier information.
     *
     * @return the user with the specified address or null if no such user exists.
     */
    public OOOUser loadUserByEmail (String email, boolean loadIdents)
        throws PersistenceException
    {
        OOOUser user = (OOOUser)loadUserWhere("where email = " + JDBCUtil.escape(email));
        if (user == null || !loadIdents) {
            return user;
        }
        loadMachineIdents(user);
        return user;
    }

    /**
     * Changes a user's username.
     *
     * @return true if the old username existed and was changed to the new name, false if the old
     * username did not exist.
     *
     * @exception UserExistsException thrown if the new name is already in use.
     */
    public boolean changeUsername (final int userId, final String username)
        throws PersistenceException, UserExistsException
    {
        return executeUpdate(new Operation<Boolean>() {
            public Boolean invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String query = "update users set username = ? where userId = ?";
                PreparedStatement stmt = null;
                try {
                    stmt = conn.prepareStatement(query);
                    stmt.setString(1, username);
                    stmt.setInt(2, userId);
                    return (stmt.executeUpdate() >= 1);

                } catch (SQLException sqe) {
                    if (liaison.isDuplicateRowException(sqe)) {
                        throw new UserExistsException("error.user_exists");
                    } else {
                        throw sqe;
                    }

                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });
    }

    /**
     * Updates the stored (already-encrypted) password for the specified user. Used to migrate a
     * legacy unsalted-MD5 password to an Argon2 hash on successful login.
     */
    public void updatePassword (final int userId, final String encrypted)
        throws PersistenceException
    {
        executeUpdate(new Operation<Object>() {
            public Object invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                PreparedStatement stmt = null;
                try {
                    stmt = conn.prepareStatement("update users set password = ? where userId = ?");
                    stmt.setString(1, encrypted);
                    stmt.setInt(2, userId);
                    stmt.executeUpdate();
                    return null;
                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });
    }

    /**
     * Updates the active state of the specified user for the specified site flag (e.g. {@link
     * OOOUser#IS_ACTIVE_YOHOHO_PLAYER}).
     */
    public void updateUserIsActive (String username, int activeFlag, boolean isActive)
        throws PersistenceException
    {
        final String query = "update users set flags = flags " +
            (isActive ? ("| " + activeFlag) : ("& ~" + activeFlag)) +
            " where username=" + JDBCUtil.escape(username);
        executeUpdate(new Operation<Object> () {
            public Object invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException {
                Statement stmt = conn.createStatement();
                try {
                    stmt.executeUpdate(query);
                } finally {
                    JDBCUtil.close(stmt);
                }
                return null;
            }
        });
    }

    /**
     * Loads up the machine ident information for the supplied user.
     */
    public void loadMachineIdents (OOOUser user)
        throws PersistenceException
    {
        // fill in this user's known machine identifiers
        List<String> idents = Lists.newArrayList();
        String where = "where USER_ID = " + user.userId;
        for (UserIdent ident : loadAll(_itable, where)) {
            idents.add(ident.machIdent);
        }
        user.machIdents = idents.toArray(new String[idents.size()]);
        // sort the idents in java to ensure correct collation
        Arrays.sort(user.machIdents);
    }

    /**
     * Returns 0 if the supplied user id is below the current throttle user id, the number of hours
     * of accounts ahead of it in the queue if it is greater.
     */
    public int checkThrottle (final int userId)
        throws PersistenceException
    {
        Integer val = execute(new Operation<Integer> () {
            public Integer invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String query = "select MAX_USERID, INCREMENT from THROTTLE";
                Statement stmt = null;
                int remain = 0;

                try {
                    stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(query);
                    if (rs.next()) {
                        int maxUserId = rs.getInt(1);
                        int increment = rs.getInt(2);
                        if (maxUserId < userId) {
                            remain = (int)Math.ceil(
                                ((float)userId-maxUserId)/increment);
                        }

                    } else {
                        log.warning("No throttle data!?");
                    }

                } finally {
                    JDBCUtil.close(stmt);
                }

                return Integer.valueOf(remain);
            }
        });
        return val.intValue();
    }

    /**
     * Updates the throttle counter by adding the increment to the smaller of the maximum user id
     * or the highest userid currently registered.  The previous maximum user id is returned along
     * with the increment and the highest userid currently registered.
     */
    public int[] updateThrottle ()
        throws PersistenceException
    {
        return executeUpdate(new Operation<int[]> () {
            public int[] invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                Statement stmt = null;
                int[] values = new int[3];

                try {
                    stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("select MAX_USERID, INCREMENT from THROTTLE");
                    if (rs.next()) {
                        values[0] = rs.getInt(1);
                        values[1] = rs.getInt(2);
                    } else {
                        log.warning("No throttle data!?");
                    }
                    rs = stmt.executeQuery("select MAX(userId) from users");
                    if (rs.next()) {
                        values[2] = rs.getInt(1);
                    } else {
                        log.warning("No MAX(userId)!?");
                    }

                    // now add the increment and update; we add the increment to the lesser of the
                    // previous max allowed userid and the actual max userid so that we don't get
                    // ahead of ourselves if there's a lull
                    int newMax = Math.min(values[0], values[2]) + values[1];
                    stmt.executeUpdate("update THROTTLE set MAX_USERID = " + newMax);

                    log.info("Updated throttle [newMax=" + newMax +
                             ", values=" + StringUtil.toString(values) + "].");

                } finally {
                    JDBCUtil.close(stmt);
                }

                return values;
            }
        });
    }

    /**
     * Returns an array of usernames registered with the specified email address or the empty array
     * if none are registered with said address.
     */
    public String[] getUsernames (final String email)
        throws PersistenceException
    {
        return execute(new Operation<String[]> () {
            public String[] invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String query = "select username from users where email = ?";
                List<String> names = Lists.newArrayList();
                PreparedStatement stmt = null;

                try {
                    stmt = conn.prepareStatement(query);
                    stmt.setString(1, email);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                } finally {
                    JDBCUtil.close(stmt);
                }

                return names.toArray(new String[names.size()]);
            }
        });
    }

    /**
     * Returns an array of usernames from the supplied collection that have the specified token set.
     */
    public List<String> getTokenUsernames (Collection<String> names, byte token)
        throws PersistenceException
    {
        StringBuilder where = new StringBuilder("where username in (");
        int idx = 0;
        for (String username : names) {
            if (idx++ > 0) {
                where.append(",");
            }
            where.append(JDBCUtil.escape(username));
        }
        where.append(") and hex(tokens) regexp '^([0-9A-F][0-9A-F])*");
        where.append(String.format("%1$02X", (int)token));
        where.append("([0-9A-F][0-9A-F])*$'");
        return getUsernamesWhere(where.toString());
    }

    /**
     * Returns an array of usernames resulting from the supplied query.  Care should be taken
     * when constructing these queries as the user table is likely to be large and a query that
     * does not make use of indices could be very slow.
     */
    public List<String> getUsernamesWhere (final String where)
        throws PersistenceException
    {
        return execute(new Operation<List<String>> () {
            public List<String> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String query = "select username from users " + where;
                List<String> names = Lists.newArrayList();
                Statement stmt = null;

                try {
                    stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(query);
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                } finally {
                    JDBCUtil.close(stmt);
                }

                return names;
            }
        });
    }


    /**
     * Creates and returns a fake user record, which will not be inserted into the database, but
     * which can be used to satisfy the early authentication of a "service" client; another process
     * connecting to the game server process.
     */
    public OOOUser createServiceUser (String username, Password password)
    {
        OOOUser user = new OOOUser();
        user.userId = -1;
        user.username = username;
        // the user record will need a dirty field mask because it will want to fiddle with it if
        // any of its fields are changed...
        user.setDirtyMask(_utable.getFieldMask());
        // ...like the password field
        user.setPassword(password);
        return user;
    }

    /**
     * Returns the total number of registered users and the number of users that registered in the
     * last 24 hours. We love the stats.
     */
    public int[] getRegiStats ()
        throws PersistenceException
    {
        return execute(new Operation<int[]> () {
            public int[] invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                int[] data = new int[2];
                Statement stmt = conn.createStatement();
                try {
                    String query = "select count(*) from history";
                    ResultSet rs = stmt.executeQuery(query);
                    if (rs.next()) {
                        data[0] = rs.getInt(1);
                    }

                    query = "select count(*) from history where created = CURRENT_DATE";
                    rs = stmt.executeQuery(query);
                    if (rs.next()) {
                        data[1] = rs.getInt(1);
                    }

                } finally {
                    JDBCUtil.close(stmt);
                }

                return data;
            }
        });
    }

    /**
     * Returns the count of registrations per day for the last <code>limit</code> days. The
     * returned tuple array contains <code>({@link Date}, count)</code> pairs.
     */
    public List<Tuple<Date,Integer>> getRecentRegCount (final int limit)
        throws PersistenceException
    {
        final List<Tuple<Date,Integer>> list = Lists.newArrayList();
        execute(new Operation<Object> () {
            public Object invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                Statement stmt = conn.createStatement();
                try {
                    String query = "select created, count(created) " +
                        "from history group by created desc limit " + limit;
                    ResultSet rs = stmt.executeQuery(query);
                    while (rs.next()) {
                        list.add(new Tuple<Date,Integer>(
                                     rs.getDate(1), Integer.valueOf(rs.getInt(2))));
                    }
                    return null;

                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });
        return list;
    }

    /**
     * Returns a HashSet of all the usernames who are subscribed.
     */
    public HashSet<String> getSubscriberUsernames (final String column)
        throws PersistenceException
    {
        return execute(new Operation<HashSet<String>> () {
            public HashSet<String> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                HashSet<String> data = new HashSet<String>();
                Statement stmt = conn.createStatement();
                try {
                    // return every username that is a subscriber
                    String query = "select username from users where " + column + " = " +
                        OOOUser.SUBSCRIBER_STATE;
                    ResultSet rs = stmt.executeQuery(query);
                    while (rs.next()) {
                        data.add(rs.getString(1));
                    }
                } finally {
                    JDBCUtil.close(stmt);
                }

                return data;
            }
        });
    }

    /**
     * Returns a new Set that is a subset of the names in the provided collection, the new Set
     * containing only usernames that have purchased coins. The original collection is not
     * modified.
     */
    public HashSet<String> filterCoinBuyers (final Collection<String> usernames)
        throws PersistenceException
    {
        return execute(new Operation<HashSet<String>>() {
            public HashSet<String> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                final HashSet<String> data = new HashSet<String>();
                JDBCUtil.BatchProcessor proc = new JDBCUtil.BatchProcessor() {
                    public void process (ResultSet row) throws SQLException {
                        data.add(row.getString(1));
                    }
                };
                String query = "select username from users, BILLAUXDATA " +
                    "where username in (#KEYS#) and BILLAUXDATA.USER_ID = " +
                    "users.userId and FIRST_COIN_BUY is not NULL";
                JDBCUtil.batchQuery(conn, query, usernames, true, FILTER_COIN_BATCH, proc);
                return data;
            }
        });
    }

    /**
     * Returns a new Set that is a subset of the userIds in the provided collection, the new Set
     * containing only userIds that have purchased coins for the first time in the interval
     * provided.  The original collection is not modified.
     */
    public HashSet<Integer> filterNewCoinBuyers (
        final Collection<Integer> userIds, final Date start, final Date end)
        throws PersistenceException
    {
        return execute(new Operation<HashSet<Integer>>() {
            public HashSet<Integer> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                final HashSet<Integer> data = new HashSet<Integer>();
                JDBCUtil.BatchProcessor proc = new JDBCUtil.BatchProcessor() {
                    public void process (ResultSet row) throws SQLException {
                        data.add(row.getInt(1));
                    }
                };
                String query = "select USER_ID from BILLAUXDATA where " +
                    "USER_ID in (#KEYS#) and FIRST_COIN_BUY >= '" + start +
                    "' and FIRST_COIN_BUY <= '" + end + "'";
                JDBCUtil.batchQuery(conn, query, userIds, true, FILTER_COIN_BATCH, proc);
                return data;
            }
        });
    }

    /**
     * Return a mapping of affliate ids to count of users who registered within the specified date
     * range (inclusive).
     */
    public IntIntMap getAffiliateRegistrationCount (Date start, Date end)
        throws PersistenceException
    {
        return getAffiliateCount(
            "from history where created >= '" + start + "' and created <= '" + end + "'");
    }

    /**
     * Return a mapping of affliate ids to currently or once subscribed users who registered within
     * the specified date range (inclusive).
     *
     * @param column the column that denotes subscription for the desired site (currently only
     * {@link OOOUser#PUZZLEPIRATES_COLUMN}).
     */
    public IntIntMap getAffiliateSubscriberCount (
        Date start, Date end, String column)
        throws PersistenceException
    {
        return getAffiliateCount("from users where created >= '" + start + "' " +
                                 "and created <= '" + end + "' and " + column + " != 0");
    }

    /**
     * Return a mapping of affliate ids to users that have purchased coins and who registered
     * within the specified date range (inclusive).
     */
    public IntIntMap getAffiliateCoinBuyerCount (Date start, Date end)
        throws PersistenceException
    {
        return getAffiliateCount("from users where created >= '" + start + "' " +
                                 "and created <= '" + end + "' " +
                                 "and (flags & " + OOOUser.HAS_BOUGHT_COINS_FLAG + ") != 0");
    }

    /**
     * Return a mapping of affliate ids to users that have purchased time and who registered within
     * the specified date range (inclusive).
     */
    public IntIntMap getAffiliateTimeBuyerCount (Date start, Date end)
        throws PersistenceException
    {
        return getAffiliateCount("from users where created >= '" + start + "' " +
                                 "and created <= '" + end + "' " +
                                 "and (flags & " + OOOUser.HAS_BOUGHT_TIME_FLAG + ") != 0");
    }

    /**
     * Helper function for {@link #getAffiliateRegistrationCount} and {@link
     * #getAffiliateSubscriberCount}.
     */
    protected IntIntMap getAffiliateCount (String fromWhere)
        throws PersistenceException
    {
        final String query = "select siteId, count(*) " + fromWhere + " group by siteId";
        return execute(new Operation<IntIntMap> () {
            public IntIntMap invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                Statement stmt = null;
                try {
                    stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(query);

                    // slurp up the results
                    IntIntMap data = new IntIntMap();
                    while (rs.next()) {
                        int siteId = rs.getInt(1);
                        int count = rs.getInt(2);
                        if (siteId < 0 || siteId == OOOUser.REFERRAL_SITE_ID) {
                            data.increment(OOOUser.REFERRAL_SITE_ID, count);
                        } else {
                            data.put(siteId, count);
                        }
                    }
                    return data;

                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });
    }

    /**
     * Get the number of created user accounts in the specified date range. Returns the count by
     * day using a LinkedHashMap ({@code Date => Count}) to preserve the date ordering returned by
     * the database.
     */
    public Map<Date,Integer> getAffiliateRegistrationCounts (Date start, Date end, int siteId)
        throws PersistenceException
    {
        return getAffiliateCounts("from history where siteId = " + siteId +
                                  " and created >= '" + start + "' and created <= '" + end + "'");
    }

    /**
     * Get the number of subscriber user accounts created in the specified date range. Returns the
     * count by day using a LinkedHashMap ({@code Date => Count}) to preserve the date ordering
     * returned by the database.
     *
     * @param siteId (aka affiliateId) the affiliate site to look up.
     * @param column the column that denotes subscription for the desired site (currently only
     * {@link OOOUser#PUZZLEPIRATES_COLUMN}).
     */
    public Map<Date,Integer> getAffiliateSubscriberCounts (
        Date start, Date end, int siteId, String column)
        throws PersistenceException
    {
        return getAffiliateCounts("from users where siteId = " + siteId +
                                  " and created >= '" + start + "' and created <= '" + end + "'" +
                                  " and " + column + " != " + OOOUser.TRIAL_STATE);
    }

    /**
     * Get the number of coin-buying user accounts created in the specified date range. Returns the
     * count by day using a LinkedHashMap ({@code Date => Count}) to preserve the date ordering
     * returned by the database.
     */
    public Map<Date, Integer> getAffiliateCoinBuyerCounts (Date start, Date end, int siteId)
        throws PersistenceException
    {
        return getAffiliateCounts("from users where siteId = " + siteId +
                                  " and created >= '" + start + "' and created <= '" + end + "'" +
                                  " and (flags & " + OOOUser.HAS_BOUGHT_COINS_FLAG + ") != 0");
    }

    /**
     * Get the number of coin-buying user accounts created in the specified date range.
     *
     * @return the count by day using a LinkedHashMap ({@code Date => Count}) to preserve the date
     * ordering returned by the database.
     */
    public Map<Date, Integer> getAffiliateTimeBuyerCounts (Date start, Date end, int siteId)
        throws PersistenceException
    {
        return getAffiliateCounts("from users where siteId = " + siteId +
                                  " and created >= '" + start + "' and created <= '" + end + "'" +
                                  " and (flags & " + OOOUser.HAS_BOUGHT_TIME_FLAG + ") != 0");
    }

    /**
     * Loads all subaffiliate mappings.
     */
    public List<AffiliateTag> loadAffiliateTags ()
        throws PersistenceException
    {
        return loadAll(_tagtable, "");
    }

    /**
     * Adds a new affiliate tag mapping and returns the assigned identifier.
     */
    public int registerAffiliateTag (String tag)
        throws PersistenceException
    {
        final AffiliateTag record = new AffiliateTag();
        record.tag = tag;

        return executeUpdate(new Operation<Integer>() {
            public Integer invoke (Connection conn, DatabaseLiaison liaison)
                throws SQLException, PersistenceException
            {
                try {
                    // first try inserting a new record
                    _tagtable.insert(conn, record);
                    return liaison.lastInsertedId(conn, _tagtable.getName(), "tagId");

                } catch (SQLException sqe) {
                    // if someone else has already added this mapping, look it up
                    if (liaison.isDuplicateRowException(sqe)) {
                        AffiliateTag erecord = _tagtable.select(
                            conn, "where TAG = " + JDBCUtil.escape(record.tag)).get();
                        if (erecord != null) {
                            return erecord.tagId;
                        }
                        log.warning("AffiliateTag table inconsistency " + record + ": " + sqe);
                        // fall through and rethrow the exception
                    }
                    throw sqe;
                }
            }
        });
    }

    /**
     * Helper function for {@link #getAffiliateRegistrationCounts} and {@link
     * #getAffiliateSubscriberCounts}.
     */
    protected Map<Date,Integer> getAffiliateCounts (String fromWhere)
        throws PersistenceException
    {
        final String query = "select created, count(*) " + fromWhere +
            " group by created order by created desc";
        return execute(new Operation<Map<Date,Integer>> () {
            public Map<Date,Integer> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                Statement stmt = null;
                try {
                    stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(query);

                    // slurp up the results
                    Map<Date,Integer> results = new LinkedHashMap<Date,Integer>();
                    while (rs.next()) {
                        results.put(rs.getDate(1), Integer.valueOf(rs.getInt(2)));
                    }
                    return results;

                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });
    }

    /**
     * Returns a mapping from affiliate id to integer array of registrations for that affiliate on
     * each day in the specified range.
     */
    public HashIntMap<int[]> getAffiliateRegistrationHistory (final Date start, final Date end)
        throws PersistenceException
    {
        return execute(new Operation<HashIntMap<int[]>> () {
            public HashIntMap<int[]> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String query = "select created, siteId, count(userId) " +
                    "from history where created >= ? and created <= ? and siteId > 0 " +
                    "group by created, siteId order by created";
                PreparedStatement stmt = null;
                try {
                    stmt = conn.prepareStatement(query);
                    stmt.setDate(1, start);
                    stmt.setDate(2, end);
                    ResultSet rs = stmt.executeQuery();

                    // count up the number of days in total
                    Calendar d1 = Calendar.getInstance();
                    d1.setTime(start);
                    Calendar d2 = Calendar.getInstance();
                    d2.setTime(end);
                    int days = Calendars.getDaysBetween(d1, d2) + 1;

                    HashIntMap<int[]> data = new HashIntMap<int[]>();
                    Date rowdate = start;
                    int didx = 0;
                    while (rs.next()) {
                        Date created = rs.getDate(1);
                        int siteId = rs.getInt(2);
                        int registrations = rs.getInt(3);

                        // advance the date if necessary
                        if (!created.equals(rowdate)) {
                            d2.setTime(created);
                            didx = Calendars.getDaysBetween(d1, d2);
                            rowdate = created;
                        }

                        // create an array for this site if necessary
                        int[] values = data.get(siteId);
                        if (values == null) {
                            data.put(siteId, values = new int[days]);
                        }
                        values[didx] = registrations;
                    }

                    return data;

                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });
    }

    /**
     * Returns a list of detail records for the users registered in our database, starting with the
     * specified record number and containing at most <code>count</code> elements.
     *
     * @param filter if true, unvalidated users and users that are already testers or the like are
     * filtered out.
     */
    public List<DetailedUser> getDetailRecords (final int start, final int count, final boolean filter)
        throws PersistenceException
    {
        return execute(new Operation<List<DetailedUser>> () {
            public List<DetailedUser> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String query = "where users.userId = AUXDATA.USER_ID ";
                if (filter) {
                    query += "AND users.flags != 0 AND users.tokens = '' ";
                }
                query += "ORDER BY userId DESC LIMIT " + start + ", " + count;
                // look up the user
                return _dtable.join(conn, "AUXDATA", query).toArrayList();
            }
        });
    }

    /**
     * Returns a list of all detail records that match the specified search string in their
     * realname, username or email address.
     */
    public List<DetailedUser> searchDetailRecords (final String term)
        throws PersistenceException
    {
        return execute(new Operation<List<DetailedUser>> () {
            public List<DetailedUser> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String newquery = "left join AUXDATA " +
                    "on userId = USER_ID where username like '%" + term +
                    "%' union select userId, username, created, " +
                    "email, birthday, gender, missive " +
                    "from users left join AUXDATA " +
                    "on userId = USER_ID where email like '%" + term +
                    "%' order by userId desc";
                return _dtable.select(conn, newquery).toArrayList();
            }
        });
    }

    /**
     * Returns a list of all detail records that exactly match the specified email.
     */
    public List<DetailedUser> searchDetailRecordsByEmail (final String email)
        throws PersistenceException
    {
        return execute(new Operation<List<DetailedUser>> () {
            public List<DetailedUser> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                String newquery = "left join AUXDATA on userId = USER_ID " +
                    "where email = '" + email + "' order by userId desc";
                return _dtable.select(conn, newquery).toArrayList();
            }
        });
    }

    /**
     * Creates a pending record for an account that has been created but not yet validated (which
     * involves the user accessing a secret URL we send to them in an email message).
     */
    public ValidateRecord createValidateRecord (int userId, boolean persist)
        throws PersistenceException
    {
        // delete any old validate mappings for the user
        update("delete from penders where userId = '" + userId + "'");

        // create a new one and insert it into the database
        ValidateRecord rec = ValidateRecord.create(userId, persist);
        insert(_vtable, rec);
        return rec;
    }

    /**
     * Fetches the validate record matching the specified secret and removes it from the pending
     * validations table.
     */
    public ValidateRecord getValidateRecord (final String secret)
        throws PersistenceException
    {
        ValidateRecord proto = new ValidateRecord();
        proto.secret = secret;
        ValidateRecord vrec = loadByExample(_vtable, proto);
        if (vrec != null) {
            // delete this record from yon table
            delete(_vtable, vrec);
        }
        return vrec;
    }

    /**
     * Fetches the validate record for the specifed user.
     */
    public ValidateRecord getValidateRecord (final int userId)
        throws PersistenceException
    {
        return load(_vtable, "where userId = '" + userId + "'");
    }

    /**
     * Purges expired validation records from the table.
     */
    public void purgeValidationRecords ()
        throws PersistenceException
    {
        update("delete from penders where inserted < DATE_SUB(CURDATE(), INTERVAL 1 MONTH)");
    }

    /**
     * Loads up the aux data record for the specified user. Returns null if none exists for that id.
     */
    public OOOAuxData getAuxRecord (final int userId)
        throws PersistenceException
    {
        return load(_atable, "where USER_ID = '" + userId + "'");
    }

    /**
     * Loads up the billing aux data record for the specified user. Returns a blank record (with
     * userId filled in) if none exists for that id.
     */
    public OOOBillAuxData getBillAuxData (int userId)
        throws PersistenceException
    {
        OOOBillAuxData baux = load(_batable, "where USER_ID = '" + userId + "'");
        if (baux == null) {
            baux = new OOOBillAuxData();
            baux.userId = userId;
        }
        return baux;
    }

    /**
     * Updates the supplied record in the database, creating the record if necessary.
     */
    public void updateBillAuxData (OOOBillAuxData record)
        throws PersistenceException
    {
        if (update(_batable, record) == 0) {
            insert(_batable, record);
        }
    }

    /**
     * Checks whether or not the specified machine identifier should be allowed to create a new
     * account.
     *
     * @return {@link #ACCESS_GRANTED} if all is well and they can proceed to create their account,
     * {@link #NEW_ACCOUNT_TAINTED} if this is a tainted machine ident, {@link
     * #NO_NEW_FREE_ACCOUNT} if this machine ident has used all of its available free accounts.
     * @deprecated Use checkCanCreate(machIdent, siteId) instead.
     */
    @Deprecated
    public int checkCanCreate (String machIdent)
        throws PersistenceException
    {
        return checkCanCreate(machIdent, -1);
    }

    /**
     * Checks whether or not the specified machine identifier should be allowed to create a new
     * account.
     *
     * @return {@link #ACCESS_GRANTED} if all is well and they can proceed to create their account,
     * {@link #NEW_ACCOUNT_TAINTED} if this is a tainted or banned machine ident, {@link
     * #NO_NEW_FREE_ACCOUNT} if this machine ident has used all of its available free accounts.
     */
    public int checkCanCreate (String machIdent, int siteId)
        throws PersistenceException
    {
        // make sure the machine identifier is not tainted or banned
        if (isTaintedIdent(machIdent) || (siteId != -1 && isBannedIdent(machIdent, siteId))) {
            return NEW_ACCOUNT_TAINTED;
        }

        // check to see if this ident has exhausted all its free accounts
        if (playedRecentFreeAccounts(machIdent, RECENT_ACCOUNT_CUTOFF, siteId) >
            MAX_FREE_ACCOUNTS_PER_MACHINE) {
            return NO_NEW_FREE_ACCOUNT;
        }

        return ACCESS_GRANTED;
    }

    /**
     * Checks whether or not the user in question should be allowed access.
     *
     * @param site the site for which we are validating the user.
     * @param newPlayer true if the user is attempting to create a new game account.
     *
     * @return {@link #ACCESS_GRANTED} if the account should be allowed access, {@link
     * #NEW_ACCOUNT_TAINTED} if this is the account's first session and they are logging in with a
     * tainted machine ident, {@link #ACCOUNT_BANNED} if this account is banned, {@link #DEADBEAT}
     * if this account needs to resolve an outstanding debt.
     */
    public int validateUser (int site, OOOUser user, String machIdent, boolean newPlayer)
        throws PersistenceException
    {
        // if this user's idents were not loaded, complain
        if (user.machIdents == OOOUser.IDENTS_NOT_LOADED) {
            log.warning("Requested to validate user with unloaded idents " +
                        "[who=" + user.username + "].", new Exception());
            // err on the side of not screwing our customers
            return ACCESS_GRANTED;
        }

        // if we have never seen them before...
        if (user.machIdents == null) {
            // add their ident to the userobject and db
            user.machIdents = new String[] { machIdent };
            addUserIdent(user.userId, machIdent);

        } else if (Arrays.binarySearch(user.machIdents, machIdent) < 0) {
            // add the machIdent to the users list of associated idents
            user.machIdents = ArrayUtil.append(user.machIdents, machIdent);
            // and slap it in the db
            addUserIdent(user.userId, machIdent);
        }

        // if this is a banned user, mark that ident
        if (user.isBanned(site)) {
            addTaintedIdent(machIdent);
            return ACCOUNT_BANNED;
        }

        // if this is a banned machIdent just return banned status
        if (isBannedIdent(machIdent, site)) {
            return ACCOUNT_BANNED;
        }

        // don't let those bastards grief us.
        if (newPlayer && (isTaintedIdent(machIdent)) ) {
            return NEW_ACCOUNT_TAINTED;
        }

        // if the user has bounced a check or reversed payment, let them know
        if (user.isDeadbeat(site)) {
            return DEADBEAT;
        }

        // if they have 0 sessions and they're not a subscriber, then make sure there aren't too
        // many other free accounts already created with this machine ident
        if (!user.isSubscriber() && !user.hasBoughtCoins() && newPlayer &&
            (playedRecentFreeAccounts(machIdent, RECENT_ACCOUNT_CUTOFF, site) >
             MAX_FREE_ACCOUNTS_PER_MACHINE)) {
            return NO_NEW_FREE_ACCOUNT;
        }

        // you're all clear kid...
        return ACCESS_GRANTED;
    }

    /**
     * Checks whether or not the machine in question should be allowed access.
     *
     * @param newPlayer true if the user is attempting to create a new game
     * account.
     *
     * @return {@link #ACCESS_GRANTED} if the account should be allowed
     * access, {@link #NEW_ACCOUNT_TAINTED} if this is the account's first
     * session and they are logging in with a tainted machine ident,
     * {@link #ACCOUNT_BANNED} if this account is banned,
     * {@link #DEADBEAT} if this account needs to resolve an outstanding debt.
     * @deprecated Use checkCanCreate(machIdent, siteId) instead.
     */
    @Deprecated
    public int validateMachIdent (String machIdent, boolean newPlayer)
        throws PersistenceException
    {
        return validateMachIdent(machIdent, newPlayer, -1);
    }

    /**
     * Checks whether or not the machine in question should be allowed access.
     *
     * @param newPlayer true if the user is attempting to create a new game
     * account.
     * @param site the site we're trying to validate this machine on
     *
     * @return {@link #ACCESS_GRANTED} if the account should be allowed
     * access, {@link #NEW_ACCOUNT_TAINTED} if this is the account's first
     * session and they are logging in with a tainted machine ident,
     * {@link #ACCOUNT_BANNED} if this account is banned,
     * {@link #DEADBEAT} if this account needs to resolve an outstanding debt.
     */
    public int validateMachIdent (String machIdent, boolean newPlayer, int site)
        throws PersistenceException
    {
        // don't let those bastards grief us.
        if ((newPlayer && (isTaintedIdent(machIdent))) ||
                (site != -1 && isBannedIdent(machIdent, site))) {
            return NEW_ACCOUNT_TAINTED;
        }

        // if they have 0 sessions and they're not a subscriber, then make sure
        // there aren't too many other free accounts already created with this
        // machine ident
        if (newPlayer && (playedRecentFreeAccounts(machIdent, RECENT_ACCOUNT_CUTOFF, site) >
             MAX_FREE_ACCOUNTS_PER_MACHINE)) {
            return NO_NEW_FREE_ACCOUNT;
        }

        // you're all clear kid...
        return ACCESS_GRANTED;
    }

    /**
     * Returns the number of free accounts that have been played at least
     * once from this machine ident and were created vaguely recently.
     * Returns the number of free accounts that have been played at least once from this machine
     * ident and were created vaguely recently.
     */
    protected int playedRecentFreeAccounts (
            final String machIdent, final int daysInThePast, final int site)
        throws PersistenceException
    {
        Integer bah = execute(new Operation<Integer> () {
            public Integer invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                Statement stmt = conn.createStatement();
                try {
                    // Compute the cutoff day for when we no longer consider an account as 'recent'
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_MONTH, daysInThePast);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

                    StringBuilder query =
                        new StringBuilder("select count(*) from users, USER_IDENTS ");
                    query.append("where users.userId = USER_IDENTS.USER_ID and ");
                    query.append("users.username not like '%=%' and ");
                    query.append("USER_IDENTS.MACH_IDENT = '").append(machIdent).append("' and ");
                    if (site == OOOUser.PUZZLEPIRATES_SITE_ID) {
                        query.append("yohoho = ").append(OOOUser.TRIAL_STATE).append(" and ");
                    }
                    query.append("flags & ").append(OOOUser.HAS_BOUGHT_COINS_FLAG).append(" = 0 ");
                    query.append("and created > '").append(sdf.format(cal.getTime())).append("'");

                    ResultSet rs = stmt.executeQuery(query.toString());
                    if (rs.next()) {
                        return Integer.valueOf(rs.getInt(1));
                    }
                    return Integer.valueOf(0);
                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });

        return bah.intValue();
    }

    /**
     * Returns a list of all users that have ever reported the specified machine identifier.
     */
    public List<Tuple<Integer,String>> getUsersOfMachIdent (String machIdent)
        throws PersistenceException
    {
        return getUsersByIdent("MACH_IDENT = '" + machIdent + "'");
    }

    /**
     * Returns a list of all users that have ever reported any of the specified machine
     * identifiers.
     */
    public List<Tuple<Integer,String>> getUsersOfMachIdents (String[] idents)
        throws PersistenceException
    {
        String idstr = StringUtil.join(idents, "', '");
        if (StringUtil.isBlank(idstr)) {
            return new ArrayList<Tuple<Integer,String>>();
        } else {
            return getUsersByIdent("MACH_IDENT in ('" + idstr + "')");
        }
    }

    /**
     * Mark this user's account as banned, update the tainted machine idents table as needed.
     *
     * @return true if the user exists and was banned, false if not.
     */
    public boolean ban (int site, String username)
       throws PersistenceException
    {
        OOOUser user = loadUser(username, true);
        if (user == null) {
            return false;
        }

        if (!user.setBanned(site, true)) {
            return false;
        }
        updateUser(user);

        for (String machIdent : user.machIdents) {
            addTaintedIdent(machIdent);
        }
        return true;
    }

    /**
     * Remove the ban from the users account, optionally untainting his machine idents.
     *
     * @return true if the user exists and was unbanned, false if not.
     */
    public boolean unban (int site, String username, boolean untaint)
        throws PersistenceException
    {
        OOOUser user = loadUser(username, true);
        if (user == null) {
            return false;
        }

        if (!user.setBanned(site, false)) {
            return false;
        }
        updateUser(user);

        if (untaint) {
            for (String machIdent : user.machIdents) {
                removeTaintedIdent(machIdent);
            }
        }
        return true;
    }

    /**
     * Taints all the machine idents belonging to the specified user.
     */
    public boolean taint (String username)
        throws PersistenceException
    {
        OOOUser user = loadUser(username, true);
        if (user == null) {
            return false;
        }

        for (String machIdent : user.machIdents) {
            addTaintedIdent(machIdent);
        }
        return true;
    }

    /**
     * Checks to see if the specified machine identifier is tainted.
     */
    public boolean isTaintedIdent (final String machIdent)
        throws PersistenceException
    {
        return load(_ttable, "WHERE MACH_IDENT = '" + machIdent + "'") != null;
    }

    /**
     * Returns the subsect of the supplied machine idents that are tainted.
     */
    public Collection<String> filterTaintedIdents (String[] idents)
        throws PersistenceException
    {
        List<String> tainted = Lists.newArrayList();
        if (idents != null && idents.length > 0) {
            String ids = JDBCUtil.escape(idents);
            for (TaintedIdent ident : loadAll(_ttable, "where MACH_IDENT in (" + ids + ")")) {
                tainted.add(ident.machIdent);
            }
        }
        return tainted;
    }

    /**
     * Store to the database that the passed in machIdent has been tainted by a banned player.
     */
    public void addTaintedIdent (final String machIdent)
        throws PersistenceException
    {
        executeUpdate(new Operation<Object> () {
            public Object invoke (Connection conn, DatabaseLiaison liaison)
                throws SQLException, PersistenceException
            {
                try {
                    TaintedIdent id = new TaintedIdent();
                    id.machIdent = machIdent;
                    _ttable.insert(conn, id);
                } catch (SQLException e) {
                    if (liaison.isDuplicateRowException(e)) {
                        // the ident is already tainted, so don't worry about it
                    } else {
                        throw e;
                    }
                }
                return null;
            }
        });
    }

    /**
     * Remove a machine ident from the tainted table.
     */
    public void removeTaintedIdent (String machIdent)
        throws PersistenceException
    {
        TaintedIdent id = new TaintedIdent();
        id.machIdent = machIdent;
        delete(_ttable, id);
    }

    /**
     * Checks to see if the specified machine identifier is banned for this site.
     */
    public boolean isBannedIdent (final String machIdent, final int siteId)
        throws PersistenceException
    {
        return load(_btable, "where SITE_ID = " + siteId + " and MACH_IDENT = '" + machIdent + "'")
            != null;
    }

    /**
     * Returns the subsect of the supplied machine idents that are banned.
     */
    public Collection<String> filterBannedIdents (String[] idents, int siteId)
        throws PersistenceException
    {
        List<String> banned = Lists.newArrayList();
        if (idents != null && idents.length > 0) {
            String ids = JDBCUtil.escape(idents);
            for (BannedIdent ident : loadAll(_btable,
                        "where SITE_ID = " + siteId + " and MACH_IDENT in (" + ids + ")")) {
                banned.add(ident.machIdent);
            }
        }
        return banned;
    }

    /**
     * Store to the database that the passed in machIdent is banned for the siteId.
     */
    public void addBannedIdent (final String machIdent, final int siteId)
        throws PersistenceException
    {
        executeUpdate(new Operation<Object> () {
            public Object invoke (Connection conn, DatabaseLiaison liaison)
                throws SQLException, PersistenceException
            {
                try {
                    BannedIdent id = new BannedIdent();
                    id.machIdent = machIdent;
                    id.siteId = siteId;
                    _btable.insert(conn, id);
                } catch (SQLException e) {
                    if (liaison.isDuplicateRowException(e)) {
                        // the ident is already banned, so don't worry about it
                    } else {
                        throw e;
                    }
                }
                return null;
            }
        });
    }

    /**
     * Remove a machine ident from the banned table.
     */
    public void removeBannedIdent (String machIdent, int siteId)
        throws PersistenceException
    {
        BannedIdent id = new BannedIdent();
        id.machIdent = machIdent;
        id.siteId = siteId;
        delete(_btable, id);
    }

    /**
     * Add the {@code userId -> machIdent} mapping to the database.
     */
    public void addUserIdent (int userId, String machIdent)
        throws PersistenceException
    {
        UserIdent id = new UserIdent();
        id.userId = userId;
        id.machIdent = machIdent;
        insert(_itable, id);
    }

    /**
     * Set the time remaining on a particular users shun in the database.
     */
    public void setUserShun (final int userId, final long shunLeft)
        throws PersistenceException
    {
        // fake up a user with the minimal info needed to update
        OOOUser minUser = new OOOUser();
        minUser.userId = userId;
        minUser.shunLeft = (int)shunLeft/MILLIS_PER_MINUTE;
        FieldMask mask = _utable.getFieldMask();
        mask.setModified("shunLeft");
        update(_utable, minUser, mask);
    }

    /**
     * Set the users black spots in the database.
     */
    public void setSpots (final int userId, final ArrayIntSet spots)
        throws PersistenceException
    {
        // fake up a user with the minimal info needed to update
        OOOUser minUser = new OOOUser();
        minUser.userId = userId;
        FieldMask mask = _utable.getFieldMask();
        minUser.setDirtyMask(mask); // give it the mask so setSpots works
        minUser.setSpots(spots);
        update(_utable, minUser, mask);
    }

    /**
     * Prunes users that have never bought coins or become a Yohoho subscriber that are the
     * specified number of days old.
     *
     * @return the number of pruned accounts.
     */
    public int pruneUsers (int daysOld)
        throws PersistenceException
    {
        Calendar when = Calendar.getInstance();
        when.add(Calendar.DATE, -1 * daysOld);
        Date since = new Date(when.getTimeInMillis());

        // if any of these flags are active, we won't prune the user
        int flags = (OOOUser.HAS_BOUGHT_COINS_FLAG | OOOUser.HAS_BOUGHT_TIME_FLAG |
                     OOOUser.IS_ACTIVE_YOHOHO_PLAYER | OOOUser.IS_ACTIVE_BANG_PLAYER |
                     OOOUser.IS_ACTIVE_GARDENS_PLAYER);

        // first delete the main user records
        String query = "delete from users where yohoho = " + OOOUser.TRIAL_STATE +
            " and flags & " + flags + " = 0 and tokens = '' and created < '" + since + "'";
        int deleted = update(query);

        // now delete orphaned records from AUXDATA and USER_IDENTS
        update("delete AUXDATA from AUXDATA left join users ON " +
               "userId = USER_ID where userId is null");
        update("delete USER_IDENTS from USER_IDENTS left join users ON " +
               "userId = USER_ID where userId is null");

        return deleted;
    }

    /**
     * Used to record email addresses of people interested in some new product that we're launching.
     */
    public void recordInterestedParty (final String product, final String email)
        throws PersistenceException
    {
        executeUpdate(new Operation<Object> () {
            public Object invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException {
                String query = "insert into INTERESTED_PARTIES " +
                    "(PRODUCT, EMAIL_ADDRESS, RECORDED) values(?, ?, ?)";
                PreparedStatement stmt = conn.prepareStatement(query);
                stmt.setString(1, product);
                stmt.setString(2, email);
                stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                try {
                    stmt.executeUpdate();
                } finally {
                    JDBCUtil.close(stmt);
                }
                return null;
            }
        });
    }

    /**
     * Returns a list of all users that have ever reported any of the specified machine idents.
     */
    protected List<Tuple<Integer,String>> getUsersByIdent (String where)
        throws PersistenceException
    {
        final String query = "select userId, username from users, USER_IDENTS " +
            "where users.userId = USER_IDENTS.USER_ID and " + where;
        return execute(new Operation<List<Tuple<Integer,String>>> () {
            public List<Tuple<Integer, String>> invoke (Connection conn, DatabaseLiaison liaison)
                throws PersistenceException, SQLException
            {
                List<Tuple<Integer,String>> info = Lists.newArrayList();
                Statement stmt = conn.createStatement();
                try {
                    ResultSet rs = stmt.executeQuery(query);
                    while (rs.next()) {
                        info.add(new Tuple<Integer, String>(
                                     Integer.valueOf(rs.getInt(1)), rs.getString(2)));
                    }
                    return info;

                } finally {
                    JDBCUtil.close(stmt);
                }
            }
        });
    }

    @Override
    protected void migrateSchema (Connection conn, DatabaseLiaison liaison)
        throws SQLException, PersistenceException
    {
        String[] usersSchema = {
            "userId INTEGER(10) PRIMARY KEY NOT NULL AUTO_INCREMENT",
            "username VARCHAR(255) NOT NULL",
            "password VARCHAR(128) NOT NULL",
            "email VARCHAR(128) NOT NULL",
            "realname VARCHAR(128) NOT NULL",
            "created DATE NOT NULL",
            "siteId INTEGER NOT NULL",
            "flags INTEGER NOT NULL",
            "tokens TINYBLOB NOT NULL",
            "yohoho TINYINT UNSIGNED NOT NULL",
            "spots VARCHAR(128) NOT NULL",
            "shunLeft INTEGER NOT NULL",
            "affiliateTagId INTEGER NOT NULL",
            "UNIQUE INDEX username_index (username)",
            "INDEX email_index (email)",
        };
        JDBCUtil.createTableIfMissing(conn, "users", usersSchema, "");

        // give the password a longer length to allow more secure hashing
        int passwordLength = JDBCUtil.getColumnSize(conn, "users", "password");
        if (passwordLength < 128) {
            JDBCUtil.changeColumn(conn, "users", "password", "password VARCHAR(128) NOT NULL");
        }

        String[] historySchema = {
            "userId INTEGER(10) PRIMARY KEY NOT NULL",
            "username VARCHAR(255) NOT NULL",
            "created DATE NOT NULL",
            "siteId INTEGER NOT NULL",
            "KEY(created)",
            "KEY(siteId)",
        };
        JDBCUtil.createTableIfMissing(conn, "history", historySchema, "");

        if (!JDBCUtil.tableExists(conn, "sites")) {
            String[] sitesSchema = {
                "siteId INTEGER(5) PRIMARY KEY NOT NULL AUTO_INCREMENT",
                "siteString VARCHAR(24) NOT NULL",
            };
            JDBCUtil.createTableIfMissing(conn, "sites", sitesSchema, "");

            String[] domainsSchema = {
                "domain VARCHAR(128) PRIMARY KEY NOT NULL",
                "siteId INTEGER(5) NOT NULL",
            };
            JDBCUtil.createTableIfMissing(conn, "domains", domainsSchema, "");

            Statement stmt = conn.createStatement();
            for (SiteData element : OOO_SITES) {
                stmt.executeUpdate("insert into sites values(" + element.siteId + ", '" +
                                   element.siteString + "')");
                stmt.executeUpdate("insert into domains values('" + element.domain + "', " +
                                   element.siteId + ")");
            }
            stmt.close();
        }

        String[] tagSchema = {
            "tagId INTEGER PRIMARY KEY AUTO_INCREMENT",
            "tag VARCHAR(255) NOT NULL",
            "UNIQUE INDEX tag_index (tag)",
        };
        JDBCUtil.createTableIfMissing(conn, "afftags", tagSchema, "");

        String[] sessionsSchema = {
            "authcode VARCHAR(32) NOT NULL PRIMARY KEY",
            "userId INTEGER(10) NOT NULL",
            "expires DATE NOT NULL",
            "INDEX userid_index (userId)",
            "INDEX expires_index (expires)"
        };
        JDBCUtil.createTableIfMissing(conn, "sessions", sessionsSchema, "");

        String[] pendersSchema = {
            "secret VARCHAR(32) PRIMARY KEY NOT NULL",
            "userId INTEGER(10) NOT NULL",
            "persist TINYINT NOT NULL",
            "inserted DATE NOT NULL",
        };
        JDBCUtil.createTableIfMissing(conn, "penders", pendersSchema, "");

        String[] auxSchema = {
            "USER_ID INTEGER NOT NULL PRIMARY KEY",
            "BIRTHDAY DATE NOT NULL",
            "GENDER TINYINT NOT NULL",
            "MISSIVE VARCHAR(255) NOT NULL",
        };
        JDBCUtil.createTableIfMissing(conn, "AUXDATA", auxSchema, "");

        String[] billAuxSchema = {
            "USER_ID INTEGER NOT NULL PRIMARY KEY",
            "FIRST_COIN_BUY DATETIME",
            "LATEST_COIN_BUY DATETIME",
        };
        JDBCUtil.createTableIfMissing(conn, "BILLAUXDATA", billAuxSchema, "");

        String[] taintedIdentsSchema = {
            "MACH_IDENT VARCHAR(255) NOT NULL",
            "PRIMARY KEY (MACH_IDENT)",
        };
        JDBCUtil.createTableIfMissing(conn, "TAINTED_IDENTS", taintedIdentsSchema, "");

        String[] bannedIdentsSchema = {
            "MACH_IDENT VARCHAR(255) NOT NULL",
            "SITE_ID INTEGER(5) NOT NULL",
            "PRIMARY KEY (MACH_IDENT, SITE_ID)",
        };
        JDBCUtil.createTableIfMissing(conn, "BANNED_IDENTS", bannedIdentsSchema, "");

        String[] userIdentsSchema = {
            "USER_ID INTEGER UNSIGNED NOT NULL",
            "MACH_IDENT VARCHAR(255) NOT NULL",
            "PRIMARY KEY (USER_ID, MACH_IDENT)",
            "INDEX (MACH_IDENT)",
        };
        JDBCUtil.createTableIfMissing(conn, "USER_IDENTS", userIdentsSchema, "");

        String[] interestedPartySchema = {
            "PRODUCT VARCHAR(255) NOT NULL",
            "EMAIL_ADDRESS VARCHAR(255) NOT NULL",
            "RECORDED DATETIME NOT NULL",
        };
        JDBCUtil.createTableIfMissing(conn, "INTERESTED_PARTIES", interestedPartySchema, "");
    }

    @Override
    protected void createTables ()
    {
        @SuppressWarnings({ "rawtypes", "unchecked" }) Table<User> utable =
            new Table(OOOUser.class, "users", "userId");
        _utable = utable;
        _dtable = new Table<DetailedUser>(DetailedUser.class, "users", "userId");
        _atable = new Table<OOOAuxData>(OOOAuxData.class, "AUXDATA", "USER_ID", true);
        _batable = new Table<OOOBillAuxData>(OOOBillAuxData.class, "BILLAUXDATA", "USER_ID", true);
        _vtable = new Table<ValidateRecord>(ValidateRecord.class, "penders", "secret");
        _itable = new Table<UserIdent>(UserIdent.class, "USER_IDENTS", "USER_ID", true);
        _ttable = new Table<TaintedIdent>(TaintedIdent.class, "TAINTED_IDENTS", "MACH_IDENT", true);
        _btable = new Table<BannedIdent>(
                BannedIdent.class, "BANNED_IDENTS", new String[] { "MACH_IDENT", "SITE_ID" }, true);
        _htable = new Table<HistoricalUser>(HistoricalUser.class, "history", "userId");
        _tagtable = new Table<AffiliateTag>(AffiliateTag.class, "afftags", "tagId");
    }

    protected void populateUser (User user, Username uname, Password pass, String email,
                                 int siteId, int tagId)
    {
        // fill in the base user information
        super.populateUser(user, uname, pass, "", email, siteId);

        // fill in the ooo-specific user information
        OOOUser ouser = (OOOUser)user;
        ouser.tokens = new byte[0];
        ouser.spots = "";
        ouser.affiliateTagId = tagId;
    }

    /** Used to auto-create site table records for our websites. */
    protected static class SiteData
    {
        public int siteId;
        public String siteString;
        public String domain;
        public SiteData (int siteId, String siteString, String domain) {
            this.siteId = siteId;
            this.siteString = siteString;
            this.domain = domain;
        }
    }

    /** The table used to select detail records. */
    protected Table<DetailedUser> _dtable;

    /** The table used to store user auxiliary data. */
    protected Table<OOOAuxData> _atable;

    /** The table used to store user billing auxiliary data. */
    protected Table<OOOBillAuxData> _batable;

    /** The table used to store pending email validations. */
    protected Table<ValidateRecord> _vtable;

    /** The table used to store the user machine ident mapping. */
    protected Table<UserIdent> _itable;

    /** The table used to store the tainted machine idents. */
    protected Table<TaintedIdent> _ttable;

    /** The table used to store the banned machine idents. */
    protected Table<BannedIdent> _btable;

    /** The table used to store our total registration history. */
    protected Table<HistoricalUser> _htable;

    /** The table used to store our affiliate tag mappings. */
    protected Table<AffiliateTag> _tagtable;

    /** The number of days in the past from now where we no longer
     * consider an account as 'recent' */
    protected static final int RECENT_ACCOUNT_CUTOFF = -3*30;

    /** The number of free accounts that can be created per machine. */
    protected static final int MAX_FREE_ACCOUNTS_PER_MACHINE = 2;

    /** The number of millis seconds in a minute*/
    protected static final int MILLIS_PER_MINUTE = 1000 * 60;

    /** Filter coin purchasers this many users at a time*/
    protected static final int FILTER_COIN_BATCH = 1000;

    /** Used to auto-create our site table. */
    protected static final SiteData[] OOO_SITES = {
        new SiteData(OOOUser.PUZZLEPIRATES_SITE_ID, "puzzlepirates", "puzzlepirates.com"),
        new SiteData(OOOUser.GAMEGARDENS_SITE_ID, "gardens", "gamegardens.com"),
        new SiteData(OOOUser.BANGHOWDY_SITE_ID, "bang", "banghowdy.com"),
    };
}
